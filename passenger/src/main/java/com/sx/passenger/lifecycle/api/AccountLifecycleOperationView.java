package com.sx.passenger.lifecycle.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 乘客可见的生命周期进度。
 *
 * <p>刻意不返回请求快照、内部错误消息、命令载荷、结果载荷和数据库主键。
 */
public record AccountLifecycleOperationView(
        String operationNo,
        String operationType,
        String status,
        long lifecycleVersion,
        boolean irreversibleStarted,
        int activeBlockerCount,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        List<StepView> steps,
        List<BlockerView> blockers) {

    public record StepView(
            String stepCode,
            String phase,
            String status,
            int sequenceNo,
            int attemptCount,
            String errorCode) {
    }

    public record BlockerView(
            String domain,
            String code,
            String resourceType,
            String resourceNo,
            String status,
            String resolutionActions) {
    }
}
