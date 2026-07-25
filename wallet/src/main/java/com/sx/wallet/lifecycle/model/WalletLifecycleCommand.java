package com.sx.wallet.lifecycle.model;

import java.time.LocalDateTime;

public record WalletLifecycleCommand(String operationNo, String stepCode, long customerId,
                                     long lifecycleVersion, String targetLifecycleStatus,
                                     String sourceEventId, LocalDateTime requestedAt) {
    public WalletLifecycleCommand {
        require(operationNo, 64, "operationNo");
        require(stepCode, 64, "stepCode");
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
        if (lifecycleVersion < 0) throw new IllegalArgumentException("lifecycleVersion不能为负数");
        require(targetLifecycleStatus, 24, "targetLifecycleStatus");
        require(sourceEventId, 64, "sourceEventId");
        if (requestedAt == null) throw new IllegalArgumentException("requestedAt不能为空");
    }

    private static void require(String value, int max, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        if (value.trim().length() > max) throw new IllegalArgumentException(name + "长度不能超过" + max);
    }
}
