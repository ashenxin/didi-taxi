package com.sx.passenger.lifecycle.orchestration;

import java.time.LocalDateTime;

public record LifecycleParticipantCommand(
        String eventId,
        String operationNo,
        String stepCode,
        long customerId,
        long lifecycleVersion,
        String targetLifecycleStatus,
        String targetDomain,
        LocalDateTime requestedAt) {
}
