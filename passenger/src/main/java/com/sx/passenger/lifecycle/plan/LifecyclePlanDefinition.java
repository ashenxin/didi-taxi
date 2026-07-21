package com.sx.passenger.lifecycle.plan;

import java.util.List;

public record LifecyclePlanDefinition(
        Integer schemaVersion,
        String code,
        Integer version,
        String operationType,
        String status,
        String description,
        List<LifecycleStepDefinition> steps) {
}
