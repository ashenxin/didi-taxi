package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;

import java.util.List;

public record LifecycleRuntimeSnapshot(LifecycleOperationEntity operation,
                                       List<LifecycleStepEntity> steps,
                                       LifecycleEventEntity requestedEvent,
                                       LifecycleOutboxEntity requestedOutbox) {
    public LifecycleRuntimeSnapshot {
        steps = List.copyOf(steps);
    }
}
