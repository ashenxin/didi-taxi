package com.sx.passenger.lifecycle.orchestration;

import org.springframework.stereotype.Service;

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
