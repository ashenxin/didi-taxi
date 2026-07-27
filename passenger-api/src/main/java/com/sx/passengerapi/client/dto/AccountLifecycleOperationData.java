package com.sx.passengerapi.client.dto;

import java.time.LocalDateTime;
import java.util.List;

/** passenger 返回的脱敏 Operation 进度。 */
public record AccountLifecycleOperationData(
        String operationNo,
        String operationType,
        String status,
        long lifecycleVersion,
        boolean irreversibleStarted,
        int activeBlockerCount,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        List<StepData> steps,
        List<BlockerData> blockers) {

    public record StepData(
            String stepCode,
            String phase,
            String status,
            int sequenceNo,
            int attemptCount,
            String errorCode) {
    }

    public record BlockerData(
            String domain,
            String code,
            String resourceType,
            String resourceNo,
            String status,
            String resolutionActions) {
    }
}
