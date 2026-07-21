package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

import java.util.List;

public record ValidatedLifecyclePlan(
        String code,
        int version,
        LifecycleOperationType operationType,
        String digest,
        List<LifecycleStepDefinition> steps) {

    public ValidatedLifecyclePlan {
        steps = List.copyOf(steps);
    }
}
