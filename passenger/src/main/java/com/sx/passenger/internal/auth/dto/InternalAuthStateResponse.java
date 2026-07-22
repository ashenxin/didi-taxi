package com.sx.passenger.internal.auth.dto;

import com.sx.passenger.auth.session.AuthSessionScope;
import com.sx.passenger.auth.session.AuthoritativeAuthState;

public record InternalAuthStateResponse(
        long customerId,
        Integer businessStatus,
        String lifecycleStatus,
        long authEpoch,
        String currentLifecycleOperationNo,
        AuthSessionScope allowedScope,
        boolean allowed) {

    public static InternalAuthStateResponse from(AuthoritativeAuthState state) {
        return new InternalAuthStateResponse(
                state.customerId(),
                state.businessStatus(),
                state.lifecycleStatus(),
                state.authEpoch(),
                state.currentLifecycleOperationNo(),
                state.allowedScope(),
                state.allowed());
    }
}
