package com.sx.passenger.lifecycle.plan;

public record LifecycleStepDefinition(
        String code,
        String participant,
        String phase,
        String executionMode,
        String criticality,
        Integer sequence,
        Integer timeoutSeconds,
        LifecycleRetryDefinition retry) {
}
