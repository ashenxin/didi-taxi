package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

import java.time.Instant;

public record CreateLifecycleSnapshotCommand(long customerId,
                                             LifecycleOperationType operationType,
                                             String idempotencyKey,
                                             String requestHash,
                                             long expectedLifecycleVersion,
                                             LifecycleActorType actorType,
                                             String actorId,
                                             String traceId,
                                             String sanitizedRequestContextJson,
                                             Instant requestedAt) {
}
