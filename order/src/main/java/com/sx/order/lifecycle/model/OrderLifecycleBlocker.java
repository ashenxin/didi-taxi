package com.sx.order.lifecycle.model;

public record OrderLifecycleBlocker(
        String code,
        String resourceType,
        String resourceNo,
        String action) {
}
