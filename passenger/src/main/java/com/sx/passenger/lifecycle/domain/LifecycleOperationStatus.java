package com.sx.passenger.lifecycle.domain;

public enum LifecycleOperationStatus {
    REQUESTED,
    FENCED,
    VALIDATING,
    BLOCKED,
    EXECUTING,
    RETRY_PENDING,
    MANUAL_REVIEW,
    COMPLETED,
    ABORTED
}
