package com.sx.passenger.auth.session;

public record AuthoritativeAuthState(
        long customerId,
        Integer businessStatus,
        String lifecycleStatus,
        long authEpoch,
        String currentLifecycleOperationNo,
        AuthSessionScope allowedScope,
        boolean allowed) {
}
