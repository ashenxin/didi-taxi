package com.sx.order.lifecycle.exception;

public class OrderLifecycleProjectionConflictException extends RuntimeException {
    public OrderLifecycleProjectionConflictException(String message) {
        super(message);
    }
}
