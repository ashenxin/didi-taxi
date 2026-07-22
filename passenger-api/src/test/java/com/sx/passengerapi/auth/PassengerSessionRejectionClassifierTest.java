package com.sx.passengerapi.auth;

import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import org.junit.jupiter.api.Test;

import static com.sx.passengerapi.auth.PassengerAuthMetrics.JwtRejectReason.EPOCH_MISMATCH;
import static com.sx.passengerapi.auth.PassengerAuthMetrics.JwtRejectReason.STATE_MISMATCH;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;

class PassengerSessionRejectionClassifierTest {

    @Test
    void classifiesOnlyAuthoritativeEpochDifferenceAsEpochMismatch() {
        ParsedPassengerJwt token = new ParsedPassengerJwt(7L, "", 9L, NORMAL, 1, null);

        assertThat(PassengerSessionRejectionClassifier.classify(token, state(10L)))
                .isEqualTo(EPOCH_MISMATCH);
        assertThat(PassengerSessionRejectionClassifier.classify(token, state(9L)))
                .isEqualTo(STATE_MISMATCH);
        assertThat(PassengerSessionRejectionClassifier.classify(token, null))
                .isEqualTo(STATE_MISMATCH);
        assertThat(PassengerSessionRejectionClassifier.classify(null, state(10L)))
                .isEqualTo(STATE_MISMATCH);
    }

    private static InternalAuthStateResponse state(long epoch) {
        return new InternalAuthStateResponse(7L, 1, "ACTIVE", epoch, null, "NORMAL", true);
    }
}
