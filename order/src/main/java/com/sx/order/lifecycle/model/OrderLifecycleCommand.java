package com.sx.order.lifecycle.model;

import java.time.LocalDateTime;

public record OrderLifecycleCommand(
        String operationNo,
        String stepCode,
        long customerId,
        String targetLifecycleStatus,
        long lifecycleVersion,
        String sourceEventId,
        LocalDateTime requestedAt) {

    public OrderLifecycleCommand {
        if (operationNo == null || operationNo.isBlank()) throw new IllegalArgumentException("operationNo不能为空");
        if (stepCode == null || stepCode.isBlank()) throw new IllegalArgumentException("stepCode不能为空");
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
        if (targetLifecycleStatus == null || targetLifecycleStatus.isBlank()) {
            throw new IllegalArgumentException("targetLifecycleStatus不能为空");
        }
        if (lifecycleVersion < 0) throw new IllegalArgumentException("lifecycleVersion不能为负数");
        if (sourceEventId == null || sourceEventId.isBlank()) throw new IllegalArgumentException("sourceEventId不能为空");
        if (requestedAt == null) throw new IllegalArgumentException("requestedAt不能为空");
        if (operationNo.trim().length() > 64) throw new IllegalArgumentException("operationNo长度不能超过64");
        if (stepCode.trim().length() > 64) throw new IllegalArgumentException("stepCode长度不能超过64");
        if (targetLifecycleStatus.trim().length() > 24) {
            throw new IllegalArgumentException("targetLifecycleStatus长度不能超过24");
        }
        if (sourceEventId.trim().length() > 64) throw new IllegalArgumentException("sourceEventId长度不能超过64");
    }

    public ApplyOrderLifecycleProjectionCommand toProjectionCommand() {
        return new ApplyOrderLifecycleProjectionCommand(customerId, 0, targetLifecycleStatus,
                lifecycleVersion, operationNo, sourceEventId, requestedAt);
    }
}
