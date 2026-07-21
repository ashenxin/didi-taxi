package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

public interface LifecyclePlanRegistry {
    ValidatedLifecyclePlan activePlan(LifecycleOperationType operationType);
    ValidatedLifecyclePlan get(String code, int version);
}
