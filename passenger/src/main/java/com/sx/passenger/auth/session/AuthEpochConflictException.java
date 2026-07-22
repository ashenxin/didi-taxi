package com.sx.passenger.auth.session;

public class AuthEpochConflictException extends RuntimeException {

    public AuthEpochConflictException() {
        super("Customer authentication epoch conflict");
    }
}
