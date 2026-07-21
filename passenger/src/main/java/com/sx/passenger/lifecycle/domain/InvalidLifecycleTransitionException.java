package com.sx.passenger.lifecycle.domain;

public class InvalidLifecycleTransitionException extends IllegalStateException {

    public InvalidLifecycleTransitionException(String message) {
        super(message);
    }
}
