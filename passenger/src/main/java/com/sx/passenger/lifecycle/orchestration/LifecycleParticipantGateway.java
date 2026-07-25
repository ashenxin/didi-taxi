package com.sx.passenger.lifecycle.orchestration;

import java.util.Optional;

public interface LifecycleParticipantGateway {
    LifecycleParticipantResult executeCheck(LifecycleParticipantCommand command);

    Optional<LifecycleParticipantResult> queryResult(
            String participantCode, String operationNo, String stepCode);
}
