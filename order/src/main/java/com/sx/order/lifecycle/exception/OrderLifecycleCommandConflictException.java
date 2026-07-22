package com.sx.order.lifecycle.exception;

public class OrderLifecycleCommandConflictException extends RuntimeException {
    public OrderLifecycleCommandConflictException(String message) {
        super(message);
    }
}
