package com.sx.passenger.lifecycle.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.LifecycleIdentifierGenerator;
import com.sx.passenger.lifecycle.application.LifecycleJson;
import com.sx.passenger.lifecycle.application.UuidLifecycleIdentifierGenerator;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleBlockerEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.CustomerPhoneBindingHistoryMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleBlockerMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class AccountCancellationOrchestrationTransaction {
    private static final String SYSTEM = "SYSTEM";
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final LifecycleBlockerMapper blockers;
    private final LifecycleEventMapper events;
    private final LifecycleOutboxMapper outbox;
    private final CustomerEntityMapper customers;
    private final CustomerPhoneBindingHistoryMapper phoneBindings;
    private final LifecycleJson json = new LifecycleJson();
    private final LifecycleIdentifierGenerator identifiers = new UuidLifecycleIdentifierGenerator();
    private final String commandTopic;
    private final String eventTopic;

    AccountCancellationOrchestrationTransaction(
            LifecycleOperationMapper operations,
            LifecycleStepMapper steps,
            LifecycleBlockerMapper blockers,
            LifecycleEventMapper events,
            LifecycleOutboxMapper outbox,
            CustomerEntityMapper customers,
            CustomerPhoneBindingHistoryMapper phoneBindings,
            @Value("${passenger.account-lifecycle.messaging.command-topic}") String commandTopic,
            @Value("${passenger.account-lifecycle.messaging.event-topic}") String eventTopic) {
        this.operations = operations;
        this.steps = steps;
        this.blockers = blockers;
        this.events = events;
        this.outbox = outbox;
        this.customers = customers;
        this.phoneBindings = phoneBindings;
        this.commandTopic = commandTopic;
        this.eventTopic = eventTopic;
    }

    @Transactional
    public LifecycleWorkItem prepare(String operationNo) {
        LifecycleOperationEntity operation = requireOperation(operationNo);
        if (isStopped(operation.getStatus())) return LifecycleWorkItem.of(LifecycleWorkItem.Kind.STOP);
        LocalDateTime now = LocalDateTime.now();
        if ("FENCED".equals(operation.getStatus())) {
            changeOperationStatus(operation, "VALIDATING", "FINAL_CHECK_STARTED", now);
        }

        List<LifecycleStepEntity> all = steps.findByOperationIdForUpdate(operation.getId());
        normalizeDueRetries(all, now);
        Integer sequence = nextBlockingSequence(all);
        if (sequence == null) return LifecycleWorkItem.of(LifecycleWorkItem.Kind.STOP);
        List<LifecycleStepEntity> group = all.stream()
                .filter(step -> step.getSequenceNo().equals(sequence))
                .toList();
        if (group.stream().anyMatch(step -> "BLOCKED".equals(step.getStatus()))) {
            changeOperationStatus(operation, "BLOCKED", "PARTICIPANT_BLOCKED", now);
            return LifecycleWorkItem.of(LifecycleWorkItem.Kind.STOP);
        }
        if (group.stream().anyMatch(step -> "MANUAL_REVIEW".equals(step.getStatus()))) {
            changeOperationStatus(operation, "MANUAL_REVIEW", "STEP_MANUAL_REVIEW", now);
            return LifecycleWorkItem.of(LifecycleWorkItem.Kind.STOP);
        }
        LifecycleStepEntity step = group.stream()
                .filter(item -> "PENDING".equals(item.getStatus()))
                .findFirst().orElse(null);
        if (step == null) {
            if (group.stream().anyMatch(item -> "RUNNING".equals(item.getStatus()))) {
                return LifecycleWorkItem.of(LifecycleWorkItem.Kind.WAIT);
            }
            LocalDateTime next = group.stream().map(LifecycleStepEntity::getNextRetryAt)
                    .filter(value -> value != null).min(Comparator.naturalOrder()).orElse(null);
            operation.setStatus("RETRY_PENDING").setNextWakeupAt(next)
                    .setRowVersion(operation.getRowVersion() + 1).setUpdatedAt(now);
            operations.updateById(operation);
            return LifecycleWorkItem.of(LifecycleWorkItem.Kind.WAIT);
        }

        if (sequence >= 300 && !Integer.valueOf(1).equals(operation.getIrreversibleStarted())) {
            operation.setIrreversibleStarted(1).setExecutionStartedAt(now);
            if (!"EXECUTING".equals(operation.getStatus())) {
                changeOperationStatus(operation, "EXECUTING", "IRREVERSIBLE_ACTION_STARTED", now);
            }
        }
        return switch (step.getExecutionMode()) {
            case "SYNC_CHECK" -> prepareRemoteCheck(operation, step, now);
            case "ASYNC_COMMAND" -> prepareAsyncCommand(operation, step, now);
            case "LOCAL_TRANSACTION" -> executeLocal(operation, step, now);
            default -> throw new IllegalStateException(
                    "未知生命周期执行方式: " + step.getExecutionMode());
        };
    }

    @Transactional
    public void applyResult(String operationNo, String stepCode, String commandEventId,
                            LifecycleParticipantResult result) {
        LifecycleOperationEntity operation = requireOperation(operationNo);
        LifecycleStepEntity step = steps.findByOperationIdForUpdate(operation.getId()).stream()
                .filter(item -> item.getStepCode().equals(stepCode)).findFirst()
                .orElseThrow(() -> new IllegalStateException("生命周期步骤不存在: " + stepCode));
        if ("SUCCEEDED".equals(step.getStatus())) return;
        if (step.getCommandEventId() != null
                && !step.getCommandEventId().equals(commandEventId)) {
            return; // 迟到的旧命令回执不能覆盖新尝试。
        }
        LocalDateTime now = LocalDateTime.now();
        String decision = result.decision() == null ? "UNKNOWN" : result.decision();
        step.setResultEventId(commandEventId).setResultSnapshot(json.write(result))
                .setTimeoutAt(null).setUpdatedAt(now);
        switch (decision) {
            case "PASS" -> {
                step.setStatus("SUCCEEDED").setCompletedAt(now).setNextRetryAt(null)
                        .setLastErrorCode(null).setLastErrorMessage(null);
                steps.updateById(step);
                resolveStepBlockers(operation, step, now);
            }
            case "BLOCKED" -> {
                step.setStatus("BLOCKED").setCompletedAt(now)
                        .setLastErrorCode("PARTICIPANT_BLOCKED")
                        .setLastErrorMessage("参与方返回结构化阻断");
                steps.updateById(step);
                storeBlockers(operation, step, result.blockers(), now);
                operation.setActiveBlockerCount(countActiveBlockers(operation.getId()));
                changeOperationStatus(operation, "BLOCKED", "PARTICIPANT_BLOCKED", now);
            }
            default -> scheduleRetry(operation, step, "PARTICIPANT_UNKNOWN",
                    "参与方结果未知，等待查询或重试", now);
        }
    }

    @Transactional
    public LifecycleParticipantCommand prepareTimedOutQuery(long stepId) {
        LifecycleStepEntity requested = steps.selectById(stepId);
        if (requested == null) return null;
        LifecycleOperationEntity operation = operations.selectById(requested.getOperationId());
        if (operation == null || isStopped(operation.getStatus())) return null;
        return command(operation, requested, requested.getCommandEventId(), LocalDateTime.now());
    }

    @Transactional
    public void handleQueryMiss(String operationNo, String stepCode) {
        LifecycleOperationEntity operation = requireOperation(operationNo);
        LifecycleStepEntity step = steps.findByOperationIdForUpdate(operation.getId()).stream()
                .filter(item -> item.getStepCode().equals(stepCode)).findFirst().orElse(null);
        if (step != null && "RUNNING".equals(step.getStatus())) {
            scheduleRetry(operation, step, "PARTICIPANT_RESULT_NOT_FOUND",
                    "参与方尚未保存命令结果", LocalDateTime.now());
        }
    }

    @Transactional
    public void requestManualRetry(String operationNo, String actor,
                                   String reason, String evidenceId) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()
                || evidenceId == null || evidenceId.isBlank()) {
            throw new IllegalArgumentException("人工恢复必须提供actor、reason和evidenceId");
        }
        LifecycleOperationEntity operation = requireOperation(operationNo);
        List<LifecycleStepEntity> all = steps.findByOperationIdForUpdate(operation.getId());
        String previousOperationStatus = operation.getStatus();
        LifecycleStepEntity target = all.stream()
                .filter(step -> "MANUAL_REVIEW".equals(step.getStatus())
                        || "BLOCKED".equals(step.getStatus()))
                .findFirst().orElseThrow(() -> new IllegalStateException("当前没有待人工恢复步骤"));
        LocalDateTime now = LocalDateTime.now();
        target.setStatus("RETRY_PENDING").setNextRetryAt(now)
                .setLastErrorCode(null).setLastErrorMessage(null).setUpdatedAt(now);
        steps.updateById(target);
        String targetOperationStatus = "BLOCKED".equals(previousOperationStatus)
                ? "VALIDATING" : "RETRY_PENDING";
        operation.setStatus(targetOperationStatus).setNextWakeupAt(now)
                .setRowVersion(operation.getRowVersion() + 1).setUpdatedAt(now);
        operations.updateById(operation);
        LifecycleEventEntity event = new LifecycleEventEntity()
                .setEventId(identifiers.nextEventId()).setOperationId(operation.getId())
                .setCustomerId(operation.getCustomerId())
                .setEventType("LIFECYCLE_MANUAL_RECOVERY_REQUESTED")
                .setFromStatus(previousOperationStatus).setToStatus(targetOperationStatus)
                .setActorType("ADMIN").setActorId(actor.trim())
                .setReasonCode(reason.trim()).setPayloadSnapshot(
                        json.write(Map.of("stepCode", target.getStepCode(),
                                "evidenceId", evidenceId.trim())))
                .setCreatedAt(now);
        if (events.insert(event) != 1) throw new IllegalStateException("人工恢复审计写入失败");
    }

    @Transactional
    public void recordPreparationFailure(String operationNo, String errorCode) {
        LifecycleOperationEntity operation = requireOperation(operationNo);
        if (isStopped(operation.getStatus())) return;
        List<LifecycleStepEntity> all = steps.findByOperationIdForUpdate(operation.getId());
        Integer sequence = nextBlockingSequence(all);
        if (sequence == null) return;
        LifecycleStepEntity target = all.stream()
                .filter(step -> step.getSequenceNo().equals(sequence))
                .filter(step -> "PENDING".equals(step.getStatus())
                        || "RETRY_PENDING".equals(step.getStatus()))
                .findFirst().orElse(null);
        if (target == null) return;
        target.setAttemptCount(target.getAttemptCount() + 1);
        scheduleRetry(operation, target, errorCode,
                "编排事务未完成，等待自动恢复", LocalDateTime.now());
    }

    private LifecycleWorkItem prepareRemoteCheck(
            LifecycleOperationEntity operation, LifecycleStepEntity step, LocalDateTime now) {
        String eventId = identifiers.nextEventId();
        markRunning(step, eventId, now);
        return LifecycleWorkItem.remote(command(operation, step, eventId, now));
    }

    private LifecycleWorkItem prepareAsyncCommand(
            LifecycleOperationEntity operation, LifecycleStepEntity step, LocalDateTime now) {
        String eventId = identifiers.nextEventId();
        markRunning(step, eventId, now);
        LifecycleParticipantCommand command = command(operation, step, eventId, now);
        LifecycleOutboxEntity message = new LifecycleOutboxEntity()
                .setEventId(eventId).setOperationId(operation.getId())
                .setAggregateType("ACCOUNT_LIFECYCLE").setAggregateId(operation.getOperationNo())
                .setEventType("ACCOUNT_LIFECYCLE_STEP_COMMAND")
                .setTargetDomain(step.getParticipantCode()).setTopic(commandTopic)
                .setPartitionKey(Long.toString(operation.getCustomerId()))
                .setPayload(json.write(command)).setStatus("PENDING")
                .setRetryCount(0).setMaxRetryCount(step.getMaxRetryCount())
                .setNextRetryAt(now).setCreatedAt(now).setUpdatedAt(now);
        if (outbox.insert(message) != 1) throw new IllegalStateException("生命周期命令Outbox写入失败");
        audit(operation, "LIFECYCLE_STEP_COMMAND_CREATED", "STEP_COMMAND_CREATED",
                json.write(Map.of("stepCode", step.getStepCode(), "eventId", eventId)), now);
        // POST_ACTION 一旦可靠落入 Outbox 就不阻塞 FINALIZE；其自身仍保留 RUNNING 供恢复诊断。
        return LifecycleWorkItem.of(LifecycleWorkItem.Kind.CONTINUE);
    }

    private LifecycleWorkItem executeLocal(
            LifecycleOperationEntity operation, LifecycleStepEntity step, LocalDateTime now) {
        String eventId = identifiers.nextEventId();
        markRunning(step, eventId, now);
        if ("ACCOUNT_FINALIZE_CANCEL".equals(step.getStepCode())) {
            if (operation.getAppliedLifecycleVersion() == null
                    || customers.finalizeAccountCancellation(operation.getCustomerId(),
                    operation.getOperationNo(), operation.getAppliedLifecycleVersion(), now) != 1) {
                throw new IllegalStateException("账号注销最终提交CAS失败");
            }
            phoneBindings.releaseActive(operation.getCustomerId(), operation.getOperationNo(), now);
        }
        step.setStatus("SUCCEEDED").setResultEventId(eventId)
                .setResultSnapshot("{\"decision\":\"PASS\",\"mode\":\"LOCAL_TRANSACTION\"}")
                .setTimeoutAt(null).setCompletedAt(now).setUpdatedAt(now);
        steps.updateById(step);
        audit(operation, "LIFECYCLE_STEP_COMPLETED", "LOCAL_STEP_COMPLETED",
                json.write(Map.of("stepCode", step.getStepCode())), now);
        if ("ACCOUNT_FINALIZE_CANCEL".equals(step.getStepCode())) {
            operation.setStatus("COMPLETED").setCompletedAt(now).setNextWakeupAt(null)
                    .setAppliedLifecycleVersion(operation.getAppliedLifecycleVersion() + 1)
                    .setRowVersion(operation.getRowVersion() + 1).setUpdatedAt(now);
            operations.updateById(operation);
            String completedEventId = identifiers.nextEventId();
            Map<String, Object> completedPayload = Map.of(
                    "eventId", completedEventId,
                    "operationNo", operation.getOperationNo(),
                    "customerId", operation.getCustomerId(),
                    "lifecycleVersion", operation.getAppliedLifecycleVersion(),
                    "lifecycleStatus", "CANCELLED",
                    "updatedAt", now);
            LifecycleOutboxEntity completedOutbox = new LifecycleOutboxEntity()
                    .setEventId(completedEventId).setOperationId(operation.getId())
                    .setAggregateType("ACCOUNT_LIFECYCLE").setAggregateId(operation.getOperationNo())
                    .setEventType("ACCOUNT_LIFECYCLE_STATUS_CHANGED").setTopic(eventTopic)
                    .setPartitionKey(Long.toString(operation.getCustomerId()))
                    .setPayload(json.write(completedPayload)).setStatus("PENDING")
                    .setRetryCount(0).setMaxRetryCount(10).setNextRetryAt(now)
                    .setCreatedAt(now).setUpdatedAt(now);
            if (outbox.insert(completedOutbox) != 1) {
                throw new IllegalStateException("注销完成事件Outbox写入失败");
            }
            audit(operation, "LIFECYCLE_OPERATION_STATUS_CHANGED", "ACCOUNT_CANCEL_COMPLETED",
                    "{\"from\":\"EXECUTING\",\"to\":\"COMPLETED\"}", now);
            return LifecycleWorkItem.of(LifecycleWorkItem.Kind.STOP);
        }
        return LifecycleWorkItem.of(LifecycleWorkItem.Kind.CONTINUE);
    }

    private void markRunning(LifecycleStepEntity step, String eventId, LocalDateTime now) {
        step.setStatus("RUNNING").setAttemptCount(step.getAttemptCount() + 1)
                .setCommandEventId(eventId).setCommandSnapshot(
                        json.write(Map.of("eventId", eventId, "stepCode", step.getStepCode())))
                .setStartedAt(step.getStartedAt() == null ? now : step.getStartedAt())
                .setTimeoutAt(now.plusSeconds(step.getTimeoutSeconds()))
                .setNextRetryAt(null).setUpdatedAt(now);
        if (steps.updateById(step) != 1) throw new IllegalStateException("生命周期步骤认领失败");
    }

    private LifecycleParticipantCommand command(
            LifecycleOperationEntity operation, LifecycleStepEntity step,
            String eventId, LocalDateTime now) {
        return new LifecycleParticipantCommand(eventId, operation.getOperationNo(), step.getStepCode(),
                operation.getCustomerId(), operation.getAppliedLifecycleVersion(),
                "CANCELLING", step.getParticipantCode(), now);
    }

    private void scheduleRetry(LifecycleOperationEntity operation, LifecycleStepEntity step,
                               String errorCode, String errorMessage, LocalDateTime now) {
        if (step.getAttemptCount() >= step.getMaxRetryCount()) {
            step.setStatus("MANUAL_REVIEW").setNextRetryAt(null)
                    .setLastErrorCode(errorCode).setLastErrorMessage(errorMessage).setUpdatedAt(now);
            steps.updateById(step);
            changeOperationStatus(operation, "MANUAL_REVIEW", "RETRY_EXHAUSTED", now);
            return;
        }
        LocalDateTime next = now.plus(LifecycleRetryPolicy.delay(
                step.getRetryInitialSeconds(), step.getAttemptCount()));
        step.setStatus("RETRY_PENDING").setNextRetryAt(next).setTimeoutAt(null)
                .setLastErrorCode(errorCode).setLastErrorMessage(errorMessage).setUpdatedAt(now);
        steps.updateById(step);
        operation.setStatus("RETRY_PENDING").setNextWakeupAt(next)
                .setLastErrorCode(errorCode).setLastErrorMessage(errorMessage)
                .setRowVersion(operation.getRowVersion() + 1).setUpdatedAt(now);
        operations.updateById(operation);
    }

    private void storeBlockers(LifecycleOperationEntity operation, LifecycleStepEntity step,
                               List<LifecycleParticipantResult.Blocker> found, LocalDateTime now) {
        for (LifecycleParticipantResult.Blocker blocker : found) {
            String resourceNo = blocker.resourceNo() == null ? "" : blocker.resourceNo();
            String key = blocker.code() + ":" + resourceNo;
            Long exists = blockers.selectCount(new LambdaQueryWrapper<LifecycleBlockerEntity>()
                    .eq(LifecycleBlockerEntity::getOperationId, operation.getId())
                    .eq(LifecycleBlockerEntity::getDomainCode, step.getParticipantCode())
                    .eq(LifecycleBlockerEntity::getBlockerKey, key));
            if (exists > 0) continue;
            LifecycleBlockerEntity entity = new LifecycleBlockerEntity()
                    .setOperationId(operation.getId()).setStepId(step.getId())
                    .setDomainCode(step.getParticipantCode()).setBlockerKey(key)
                    .setBlockerType(blocker.code()).setResourceType(blocker.resourceType())
                    .setResourceId(blocker.resourceNo()).setStatus("ACTIVE")
                    .setResolutionActions(json.write(List.of(blocker.action())))
                    .setSnapshotJson("{}").setDetectedAt(now).setLastConfirmedAt(now)
                    .setCreatedAt(now).setUpdatedAt(now);
            blockers.insert(entity);
        }
    }

    private void resolveStepBlockers(
            LifecycleOperationEntity operation, LifecycleStepEntity step, LocalDateTime now) {
        List<LifecycleBlockerEntity> active = blockers.selectList(
                new LambdaQueryWrapper<LifecycleBlockerEntity>()
                        .eq(LifecycleBlockerEntity::getOperationId, operation.getId())
                        .eq(LifecycleBlockerEntity::getStepId, step.getId())
                        .eq(LifecycleBlockerEntity::getStatus, "ACTIVE"));
        active.forEach(item -> {
            item.setStatus("RESOLVED").setResolvedAt(now)
                    .setResolutionReason("PARTICIPANT_RECHECK_PASS")
                    .setLastConfirmedAt(now).setUpdatedAt(now);
            blockers.updateById(item);
        });
        operation.setActiveBlockerCount(countActiveBlockers(operation.getId()));
        operations.updateById(operation);
    }

    private int countActiveBlockers(long operationId) {
        return Math.toIntExact(blockers.selectCount(new LambdaQueryWrapper<LifecycleBlockerEntity>()
                .eq(LifecycleBlockerEntity::getOperationId, operationId)
                .eq(LifecycleBlockerEntity::getStatus, "ACTIVE")));
    }

    private void normalizeDueRetries(List<LifecycleStepEntity> all, LocalDateTime now) {
        all.stream().filter(step -> "RETRY_PENDING".equals(step.getStatus()))
                .filter(step -> step.getNextRetryAt() == null || !step.getNextRetryAt().isAfter(now))
                .forEach(step -> {
                    step.setStatus("PENDING").setNextRetryAt(null).setUpdatedAt(now);
                    steps.updateById(step);
                });
    }

    private static Integer nextBlockingSequence(List<LifecycleStepEntity> all) {
        return all.stream()
                .filter(step -> !isTerminal(step.getStatus()))
                .filter(step -> !("POST_ACTION".equals(step.getCriticality())
                        && "RUNNING".equals(step.getStatus())))
                .map(LifecycleStepEntity::getSequenceNo).min(Integer::compareTo).orElse(null);
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "SKIPPED".equals(status) || "CANCELLED".equals(status);
    }

    private static boolean isStopped(String status) {
        return "BLOCKED".equals(status) || "MANUAL_REVIEW".equals(status)
                || "COMPLETED".equals(status) || "ABORTED".equals(status);
    }

    private LifecycleOperationEntity requireOperation(String operationNo) {
        LifecycleOperationEntity operation = operations.findByOperationNoForUpdate(operationNo);
        if (operation == null || !"ACCOUNT_CANCEL".equals(operation.getOperationType())) {
            throw new IllegalArgumentException("账号注销生命周期操作不存在");
        }
        return operation;
    }

    private void changeOperationStatus(LifecycleOperationEntity operation, String target,
                                       String reason, LocalDateTime now) {
        if (target.equals(operation.getStatus())) return;
        String from = operation.getStatus();
        operation.setStatus(target).setRowVersion(operation.getRowVersion() + 1).setUpdatedAt(now);
        if (!"RETRY_PENDING".equals(target)) operation.setNextWakeupAt(null);
        operations.updateById(operation);
        audit(operation, "LIFECYCLE_OPERATION_STATUS_CHANGED", reason,
                json.write(Map.of("from", from, "to", target)), now);
    }

    private void audit(LifecycleOperationEntity operation, String type, String reason,
                       String payload, LocalDateTime now) {
        LifecycleEventEntity event = new LifecycleEventEntity()
                .setEventId(identifiers.nextEventId()).setOperationId(operation.getId())
                .setCustomerId(operation.getCustomerId()).setEventType(type)
                .setToStatus(operation.getStatus()).setActorType(SYSTEM)
                .setReasonCode(reason).setPayloadSnapshot(payload).setCreatedAt(now);
        if (events.insert(event) != 1) throw new IllegalStateException("生命周期审计事件写入失败");
    }
}
