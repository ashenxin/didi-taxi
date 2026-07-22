package com.sx.passengerapi.auth;

import com.sx.passengerapi.client.dto.InternalAuthStateResponse;

/** HTTP 与 WebSocket 共用的会话失效指标分类器。 */
public final class PassengerSessionRejectionClassifier {

    private PassengerSessionRejectionClassifier() {
    }

    public static PassengerAuthMetrics.JwtRejectReason classify(
            ParsedPassengerJwt token,
            InternalAuthStateResponse authoritativeState) {
        return token != null && authoritativeState != null
                && token.authEpoch() != authoritativeState.getAuthEpoch()
                ? PassengerAuthMetrics.JwtRejectReason.EPOCH_MISMATCH
                : PassengerAuthMetrics.JwtRejectReason.STATE_MISMATCH;
    }
}
