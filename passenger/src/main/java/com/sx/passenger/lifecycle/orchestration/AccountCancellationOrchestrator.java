package com.sx.passenger.lifecycle.orchestration;

import org.springframework.stereotype.Service;

/**
 * 注销 Saga 的外层推进循环。
 *
 * <p>数据库决策由事务组件完成，远程调用在事务外执行；每次最多内联推进固定步数，
 * 防止异常计划或状态循环长期占用 XXL-JOB/请求线程。
 */
@Service
public class AccountCancellationOrchestrator {
    private static final int MAX_INLINE_ADVANCES = 32;
    private final AccountCancellationOrchestrationTransaction transaction;
    private final LifecycleParticipantGateway participants;

    public AccountCancellationOrchestrator(
            AccountCancellationOrchestrationTransaction transaction,
            LifecycleParticipantGateway participants) {
        this.transaction = transaction;
        this.participants = participants;
    }

    /** 从持久化状态继续推进指定 Operation，直到等待、停止或达到内联上限。 */
    public void resume(String operationNo) {
        for (int i = 0; i < MAX_INLINE_ADVANCES; i++) {
            LifecycleWorkItem work;
            try {
                work = transaction.prepare(operationNo);
            } catch (IllegalStateException ex) {
                transaction.recordPreparationFailure(
                        operationNo, "ORCHESTRATION_PREPARATION_FAILED");
                return;
            }
            switch (work.kind()) {
                case STOP, WAIT -> { return; }
                case CONTINUE -> { continue; }
                case REMOTE_CHECK -> {
                    LifecycleParticipantCommand command = work.command();
                    LifecycleParticipantResult result;
                    try {
                        result = participants.executeCheck(command);
                    } catch (RuntimeException ex) {
                        result = new LifecycleParticipantResult(
                                "UNKNOWN", null, java.util.Map.of("error", "PARTICIPANT_UNAVAILABLE"));
                    }
                    transaction.applyResult(operationNo, command.stepCode(), command.eventId(), result);
                }
            }
        }
        throw new IllegalStateException("生命周期编排单次推进次数超过安全上限");
    }
}
