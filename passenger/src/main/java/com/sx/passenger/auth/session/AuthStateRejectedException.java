package com.sx.passenger.auth.session;

public class AuthStateRejectedException extends RuntimeException {

    public AuthStateRejectedException() {
        super("Customer authentication state is not allowed");
    }
}
