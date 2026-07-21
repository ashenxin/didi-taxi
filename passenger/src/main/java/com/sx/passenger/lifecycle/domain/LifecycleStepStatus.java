package com.sx.passenger.lifecycle.domain;

public enum LifecycleStepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    BLOCKED,
    RETRY_PENDING,
    MANUAL_REVIEW,
    SKIPPED,
    CANCELLED
}
