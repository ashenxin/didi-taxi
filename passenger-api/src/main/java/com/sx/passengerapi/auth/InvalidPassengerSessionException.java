package com.sx.passengerapi.auth;

public class InvalidPassengerSessionException extends RuntimeException {

    public InvalidPassengerSessionException() {
        super("invalid passenger session");
    }
}
