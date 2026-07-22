package com.sx.passengerapi.auth.action;

import com.sx.passengerapi.auth.PassengerSessionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ACCOUNT_CANCEL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.DEBT_PAYMENT;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ORDER_CANCEL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ORDER_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.PROFILE_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.RIDE_CREATE;
import static com.sx.passengerapi.auth.action.PassengerActionCode.SESSION_LOGOUT;
import static com.sx.passengerapi.auth.action.PassengerActionDecision.ALLOW;
import static com.sx.passengerapi.auth.action.PassengerActionDecision.DENY;
import static com.sx.passengerapi.auth.action.PassengerActionDecision.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class PassengerActionPolicyTest {

    private final PassengerActionPolicy policy = new PassengerActionPolicy();

    @ParameterizedTest
    @EnumSource(PassengerActionCode.class)
    void activeNormalAllowsEveryKnownAction(PassengerActionCode action) {
        assertThat(policy.decide(0, "ACTIVE", NORMAL, action)).isEqualTo(ALLOW);
    }

    @Test
    void cancellingRestrictedAllowsOnlyRiskReductionAndReadActions() {
        assertThat(policy.decide(0, "CANCELLING", LIFECYCLE_RESTRICTED, ORDER_READ)).isEqualTo(ALLOW);
        assertThat(policy.decide(0, "CANCELLING", LIFECYCLE_RESTRICTED, ORDER_CANCEL)).isEqualTo(ALLOW);
        assertThat(policy.decide(0, "CANCELLING", LIFECYCLE_RESTRICTED, DEBT_PAYMENT)).isEqualTo(ALLOW);
        assertThat(policy.decide(0, "CANCELLING", LIFECYCLE_RESTRICTED, PROFILE_READ)).isEqualTo(ALLOW);
        assertThat(policy.decide(0, "CANCELLING", LIFECYCLE_RESTRICTED, SESSION_LOGOUT)).isEqualTo(ALLOW);
        assertThat(policy.decide(0, "CANCELLING", LIFECYCLE_RESTRICTED, ACCOUNT_CANCEL)).isEqualTo(ALLOW);
        assertThat(policy.decide(0, "CANCELLING", LIFECYCLE_RESTRICTED, RIDE_CREATE)).isEqualTo(DENY);
    }

    @Test
    void cancelledDisabledAndMismatchedScopeFailClosed() {
        assertThat(policy.decide(0, "CANCELLED", NORMAL, ORDER_READ)).isEqualTo(DENY);
        assertThat(policy.decide(1, "ACTIVE", NORMAL, ORDER_READ)).isEqualTo(DENY);
        assertThat(policy.decide(null, "ACTIVE", NORMAL, ORDER_READ)).isEqualTo(DENY);
        assertThat(policy.decide(0, "ACTIVE", LIFECYCLE_RESTRICTED, ORDER_READ)).isEqualTo(DENY);
        assertThat(policy.decide(0, "CANCELLING", NORMAL, ORDER_READ)).isEqualTo(DENY);
    }

    @Test
    void unknownFactsReturnUnknown() {
        assertThat(policy.decide(0, "FUTURE", NORMAL, ORDER_READ)).isEqualTo(UNKNOWN);
        assertThat(policy.decide(0, null, NORMAL, ORDER_READ)).isEqualTo(UNKNOWN);
        assertThat(policy.decide(0, "ACTIVE", (PassengerSessionScope) null, ORDER_READ)).isEqualTo(UNKNOWN);
    }
}
