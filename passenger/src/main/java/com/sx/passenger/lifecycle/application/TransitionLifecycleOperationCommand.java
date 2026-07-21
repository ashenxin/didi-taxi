package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationStatus;

import java.time.Instant;

public record TransitionLifecycleOperationCommand(long operationId,
                                                  long expectedRowVersion,
                                                  LifecycleOperationStatus targetStatus,
                                                  LifecycleActorType actorType,
                                                  String actorId,
                                                  String reasonCode,
                                                  String traceId,
                                                  String sanitizedPayloadJson,
                                                  Instant occurredAt) {
}
