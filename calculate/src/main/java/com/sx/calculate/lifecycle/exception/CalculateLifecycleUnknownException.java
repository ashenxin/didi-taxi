package com.sx.calculate.lifecycle.exception;

public class CalculateLifecycleUnknownException extends RuntimeException {
    public CalculateLifecycleUnknownException(String message) {
        super(message);
    }

    public CalculateLifecycleUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
