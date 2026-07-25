package com.sx.calculate.lifecycle.messaging;

import com.sx.calculate.lifecycle.model.CalculateLifecycleCommand;

import java.time.LocalDateTime;

public record CalculateLifecycleCommandMessage(
        String eventId,
        String operationNo,
        String stepCode,
        long customerId,
        long lifecycleVersion,
        String targetLifecycleStatus,
        String targetDomain,
        LocalDateTime requestedAt) {

    CalculateLifecycleCommand toCommand() {
        return new CalculateLifecycleCommand(operationNo, stepCode, customerId, lifecycleVersion,
                targetLifecycleStatus, eventId, requestedAt);
    }
}
