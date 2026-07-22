package com.sx.passengerapi.auth.action;

import com.sx.passengerapi.auth.PassengerSessionScope;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ACCOUNT_CANCEL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.DEBT_PAYMENT;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ORDER_CANCEL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ORDER_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.PROFILE_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.SESSION_LOGOUT;
import static com.sx.passengerapi.auth.action.PassengerActionDecision.ALLOW;
import static com.sx.passengerapi.auth.action.PassengerActionDecision.DENY;
import static com.sx.passengerapi.auth.action.PassengerActionDecision.UNKNOWN;

@Component
public class PassengerActionPolicy {

    private static final Set<PassengerActionCode> RESTRICTED_ALLOWED = EnumSet.of(
            ORDER_READ, ORDER_CANCEL, DEBT_PAYMENT, PROFILE_READ, SESSION_LOGOUT, ACCOUNT_CANCEL);

    public PassengerActionDecision decide(Integer businessStatus,
                                          String lifecycleStatus,
                                          PassengerSessionScope scope,
                                          PassengerActionCode action) {
        if (businessStatus == null || businessStatus != 0) {
            return DENY;
        }
        if (lifecycleStatus == null || scope == null || action == null) {
            return UNKNOWN;
        }
        if ("CANCELLED".equals(lifecycleStatus)) {
            return DENY;
        }
        if ("ACTIVE".equals(lifecycleStatus)) {
            return scope == NORMAL ? ALLOW : DENY;
        }
        if ("CANCELLING".equals(lifecycleStatus)) {
            return scope == LIFECYCLE_RESTRICTED && RESTRICTED_ALLOWED.contains(action) ? ALLOW : DENY;
        }
        return UNKNOWN;
    }
}
