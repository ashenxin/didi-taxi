package com.sx.order.lifecycle.exception;

public class OrderLifecycleParticipantUnavailableException extends RuntimeException {
    public OrderLifecycleParticipantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
