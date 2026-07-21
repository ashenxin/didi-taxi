package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;

import java.util.Optional;

public interface LifecycleSnapshotStore {
    Optional<LifecycleOperationEntity> findByIdempotency(long customerId,
                                                          LifecycleOperationType type,
                                                          String idempotencyKey);
    void persistNew(LifecycleRuntimeSnapshot snapshot);
}
