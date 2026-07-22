package com.sx.passenger.lifecycle.application.phone;

import java.time.Instant;

public record ChangeCustomerPhoneCommand(long customerId,
                                         long expectedLifecycleVersion,
                                         String newPhone,
                                         String otpCode,
                                         String idempotencyKey,
                                         String actorId,
                                         String traceId,
                                         String sanitizedRequestContextJson,
                                         Instant requestedAt) {
    public ChangeCustomerPhoneCommand {
        if (customerId <= 0) throw new IllegalArgumentException("customerId must be positive");
        if (expectedLifecycleVersion < 0) {
            throw new IllegalArgumentException("expectedLifecycleVersion must not be negative");
        }
        if (newPhone == null || !newPhone.matches("1\\d{10}")) {
            throw new IllegalArgumentException("newPhone must be a valid mobile phone");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must not exceed 128 characters");
        }
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("actorId must not be blank");
        if (actorId.length() > 64) throw new IllegalArgumentException("actorId must not exceed 64 characters");
        if (traceId != null && traceId.length() > 64) {
            throw new IllegalArgumentException("traceId must not exceed 64 characters");
        }
        if (sanitizedRequestContextJson == null || sanitizedRequestContextJson.isBlank()) {
            throw new IllegalArgumentException("sanitizedRequestContextJson must not be blank");
        }
        if (requestedAt == null) throw new IllegalArgumentException("requestedAt must not be null");
    }
}
