package com.sx.order.lifecycle.model;

import java.time.LocalDateTime;

public record ApplyOrderLifecycleProjectionCommand(
        long customerId,
        int businessStatus,
        String lifecycleStatus,
        long lifecycleVersion,
        String operationNo,
        String sourceEventId,
        LocalDateTime updatedAt) {

    public ApplyOrderLifecycleProjectionCommand {
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
        if (lifecycleVersion < 0) throw new IllegalArgumentException("lifecycleVersion不能为负数");
        if (lifecycleStatus == null || lifecycleStatus.isBlank()) {
            throw new IllegalArgumentException("lifecycleStatus不能为空");
        }
        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new IllegalArgumentException("sourceEventId不能为空");
        }
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt不能为空");
    }
}
