package com.sx.passenger.lifecycle.orchestration;

import com.sx.passenger.lifecycle.job.LifecycleJobBatchResult;
import com.sx.passenger.lifecycle.job.LifecycleJobProperties;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import com.sx.passenger.time.PassengerPersistenceTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 生命周期故障恢复服务。
 *
 * <p>处理两类遗留工作：达到 next_wakeup_at 的 Operation，以及已经超过 timeout_at
 * 的 RUNNING Step。单条失败不会终止整批，并受批次截止时间约束。
 */
@Service
@Slf4j
public class LifecycleRecoveryService {
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final AccountCancellationOrchestrationTransaction transaction;
    private final AccountCancellationOrchestrator orchestrator;
    private final LifecycleParticipantGateway participants;
    private final LifecycleJobProperties properties;

    public LifecycleRecoveryService(
            LifecycleOperationMapper operations,
            LifecycleStepMapper steps,
            AccountCancellationOrchestrationTransaction transaction,
            AccountCancellationOrchestrator orchestrator,
            LifecycleParticipantGateway participants,
            LifecycleJobProperties properties) {
        this.operations = operations;
        this.steps = steps;
        this.transaction = transaction;
        this.orchestrator = orchestrator;
        this.participants = participants;
        this.properties = properties;
    }

    /** 在统一时间预算内依次恢复超时步骤和到期 Operation。 */
    public LifecycleJobBatchResult recover() {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(
                properties.getRecovery().getBatchDeadlineSeconds());
        LifecycleJobBatchResult timedOut = recoverTimedOutSteps(
                properties.getRecovery().getStepBatchSize(), deadline);
        LifecycleJobBatchResult due = recoverDueOperations(
                properties.getRecovery().getOperationBatchSize(), deadline);
        return new LifecycleJobBatchResult(
                timedOut.scanned() + due.scanned(),
                timedOut.claimed() + due.claimed(),
                timedOut.succeeded() + due.succeeded(),
                timedOut.failed() + due.failed(),
                timedOut.exhausted() + due.exhausted(),
                timedOut.skipped() + due.skipped(),
                elapsedMs(started));
    }

    /** 独立恢复指定数量的到期 Operation，主要用于测试和手工调用。 */
    public LifecycleJobBatchResult recoverDueOperations(int limit) {
        long started = System.nanoTime();
        return recoverDueOperations(limit, started + TimeUnit.SECONDS.toNanos(
                properties.getRecovery().getBatchDeadlineSeconds()));
    }

    private LifecycleJobBatchResult recoverDueOperations(int limit, long deadline) {
        long started = System.nanoTime();
        List<LifecycleOperationEntity> due =
                operations.findDueForRecovery(PassengerPersistenceTime.now(), limit);
        int claimed = 0;
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        for (int index = 0; index < due.size(); index++) {
            if (System.nanoTime() >= deadline) {
                skipped += due.size() - index;
                break;
            }
            LifecycleOperationEntity operation = due.get(index);
            claimed++;
            try {
                orchestrator.resume(operation.getOperationNo());
                succeeded++;
            } catch (RuntimeException ex) {
                failed++;
                log.error("生命周期Operation恢复失败 operationNo={} status={} errorType={}",
                        operation.getOperationNo(), operation.getStatus(),
                        ex.getClass().getSimpleName(), ex);
            }
        }
        return new LifecycleJobBatchResult(
                due.size(), claimed, succeeded, failed, 0, skipped, elapsedMs(started));
    }

    /** 独立处理指定数量的超时 RUNNING Step。 */
    public LifecycleJobBatchResult recoverTimedOutSteps(int limit) {
        long started = System.nanoTime();
        return recoverTimedOutSteps(limit, started + TimeUnit.SECONDS.toNanos(
                properties.getRecovery().getBatchDeadlineSeconds()));
    }

    private LifecycleJobBatchResult recoverTimedOutSteps(int limit, long deadline) {
        long started = System.nanoTime();
        List<LifecycleStepEntity> timedOut = steps.findTimedOutRunning(PassengerPersistenceTime.now(), limit);
        int claimed = 0;
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        for (int index = 0; index < timedOut.size(); index++) {
            if (System.nanoTime() >= deadline) {
                skipped += timedOut.size() - index;
                break;
            }
            LifecycleStepEntity step = timedOut.get(index);
            claimed++;
            try {
                LifecycleParticipantCommand command =
                        transaction.prepareTimedOutQuery(step.getId());
                if (command == null || !isQueryable(command.targetDomain())) {
                    skipped++;
                    continue;
                }
                Optional<LifecycleParticipantResult> result = participants.queryResult(
                        command.targetDomain(), command.operationNo(), command.stepCode());
                if (result.isPresent()) {
                    transaction.applyResult(command.operationNo(), command.stepCode(),
                            command.eventId(), result.get());
                    orchestrator.resume(command.operationNo());
                } else {
                    transaction.handleQueryMiss(command.operationNo(), command.stepCode());
                }
                succeeded++;
            } catch (RuntimeException ex) {
                failed++;
                log.error("生命周期超时Step恢复失败 stepId={} stepCode={} participant={} errorType={}",
                        step.getId(), step.getStepCode(), step.getParticipantCode(),
                        ex.getClass().getSimpleName(), ex);
            }
        }
        return new LifecycleJobBatchResult(
                timedOut.size(), claimed, succeeded, failed, 0, skipped,
                elapsedMs(started));
    }

    /** 只有提供结果查询接口的远程域才能对超时命令执行查询恢复。 */
    private static boolean isQueryable(String participant) {
        return "ORDER".equals(participant) || "CALCULATE".equals(participant)
                || "WALLET".equals(participant);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
