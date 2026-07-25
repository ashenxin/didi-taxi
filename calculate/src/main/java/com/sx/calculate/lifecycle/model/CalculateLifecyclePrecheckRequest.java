package com.sx.calculate.lifecycle.model;

public record CalculateLifecyclePrecheckRequest(long customerId) {
    public CalculateLifecyclePrecheckRequest {
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
    }
}
