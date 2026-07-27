package com.sx.passenger.lifecycle.application.cancel;

import java.time.Instant;

/**
 * 消费注销 OTP 并建立账号注销栅栏的命令。
 *
 * @param expectedLifecycleVersion 客户端发起时看到的账号生命周期版本
 * @param otpCode ACCOUNT_CANCEL 用途的一次性验证码
 * @param idempotencyKey 重试时保持不变的幂等键
 * @param sanitizedRequestContextJson 已脱敏的设备和请求上下文
 */
public record FenceAccountCancellationCommand(long customerId,
                                              long expectedLifecycleVersion,
                                              String otpCode,
                                              String idempotencyKey,
                                              String actorId,
                                              String traceId,
                                              String sanitizedRequestContextJson,
                                              Instant requestedAt) {
    public FenceAccountCancellationCommand {
        if (customerId <= 0) throw new IllegalArgumentException("customerId must be positive");
        if (expectedLifecycleVersion < 0) {
            throw new IllegalArgumentException("expectedLifecycleVersion must not be negative");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must not exceed 128 characters");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        if (actorId.length() > 64) {
            throw new IllegalArgumentException("actorId must not exceed 64 characters");
        }
        if (traceId != null && traceId.length() > 64) {
            throw new IllegalArgumentException("traceId must not exceed 64 characters");
        }
        if (sanitizedRequestContextJson == null || sanitizedRequestContextJson.isBlank()) {
            throw new IllegalArgumentException("sanitizedRequestContextJson must not be blank");
        }
        if (requestedAt == null) throw new IllegalArgumentException("requestedAt must not be null");
    }
}
