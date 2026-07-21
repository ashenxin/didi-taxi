package com.sx.passenger.lifecycle.plan;

public class InvalidLifecyclePlanException extends IllegalStateException {
    public InvalidLifecyclePlanException(String message) {
        super(message);
    }

    public InvalidLifecyclePlanException(String message, Throwable cause) {
        super(message, cause);
    }
}
