package com.sx.passenger.lifecycle.plan;

public record LifecycleRetryDefinition(Integer maxAttempts, Integer initialIntervalSeconds) {
}
