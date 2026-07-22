package com.sx.passengerapi.auth;

public record PassengerAuthContext(
        long customerId,
        String phone,
        long authEpoch,
        PassengerSessionScope scope,
        int audit,
        String operationNo) {

    public static PassengerAuthContext from(ParsedPassengerJwt token) {
        return new PassengerAuthContext(
                token.customerId(),
                token.phone(),
                token.authEpoch(),
                token.scope(),
                token.audit(),
                token.operationNo());
    }
}
