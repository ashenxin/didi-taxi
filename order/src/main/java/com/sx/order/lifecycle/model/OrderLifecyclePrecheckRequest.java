package com.sx.order.lifecycle.model;

public record OrderLifecyclePrecheckRequest(long customerId) {
    public OrderLifecyclePrecheckRequest {
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
    }
}
