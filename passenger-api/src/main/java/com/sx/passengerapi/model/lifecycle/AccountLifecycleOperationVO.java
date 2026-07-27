package com.sx.passengerapi.model.lifecycle;

import java.time.LocalDateTime;
import java.util.List;

/** 面向乘客的生命周期进度视图。 */
public record AccountLifecycleOperationVO(
        String operationNo,
        String operationType,
        String status,
        long lifecycleVersion,
        boolean irreversibleStarted,
        int activeBlockerCount,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        List<StepVO> steps,
        List<BlockerVO> blockers) {

    public record StepVO(
            String stepCode, String phase, String status,
            int sequenceNo, int attemptCount, String errorCode) {
    }

    public record BlockerVO(
            String domain, String code, String resourceType,
            String resourceNo, String status, String resolutionActions) {
    }
}
