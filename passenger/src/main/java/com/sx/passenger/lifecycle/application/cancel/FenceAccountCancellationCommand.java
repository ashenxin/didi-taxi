package com.sx.passenger.lifecycle.application.cancel;

import java.time.Instant;

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
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        if (sanitizedRequestContextJson == null || sanitizedRequestContextJson.isBlank()) {
            throw new IllegalArgumentException("sanitizedRequestContextJson must not be blank");
        }
        if (requestedAt == null) throw new IllegalArgumentException("requestedAt must not be null");
    }
}
