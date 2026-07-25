package com.sx.calculate.lifecycle.model;

public record CalculateLifecycleBlocker(
        String code,
        String resourceType,
        String resourceNo,
        String action) {
}
