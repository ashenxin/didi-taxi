package com.sx.wallet.lifecycle.model;

import java.time.LocalDateTime;

/** Wallet 本地生命周期投影更新；终态 ACTIVE/CANCELLED 允许 operationNo 为空。 */
public record ApplyWalletLifecycleProjectionCommand(
        long customerId,
        int businessStatus,
        String lifecycleStatus,
        long lifecycleVersion,
        String operationNo,
        String sourceEventId,
        LocalDateTime updatedAt) {

    public ApplyWalletLifecycleProjectionCommand {
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
        if (lifecycleVersion < 0) throw new IllegalArgumentException("lifecycleVersion不能为负数");
        require(lifecycleStatus, 24, "lifecycleStatus");
        require(sourceEventId, 64, "sourceEventId");
        if (operationNo != null && operationNo.trim().length() > 64) {
            throw new IllegalArgumentException("operationNo长度不能超过64");
        }
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt不能为空");
    }

    private static void require(String value, int max, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        if (value.trim().length() > max) throw new IllegalArgumentException(name + "长度不能超过" + max);
    }
}
