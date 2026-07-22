package com.sx.passengerapi.auth;

import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;

@Service
public class PassengerAuthDecisionService {

    public PassengerAuthContext verify(ParsedPassengerJwt token,
                                       InternalAuthStateResponse state,
                                       int channelAudit) {
        if (token == null || state == null
                || !state.isAllowed()
                || state.getCustomerId() != token.customerId()
                || state.getAuthEpoch() != token.authEpoch()
                || !token.scope().name().equals(state.getAllowedScope())
                || token.audit() != channelAudit
                || !Objects.equals(state.getCurrentLifecycleOperationNo(), token.operationNo())
                || !lifecycleMatches(token.scope(), state.getLifecycleStatus())) {
            throw new InvalidPassengerSessionException();
        }
        return PassengerAuthContext.from(token);
    }

    private static boolean lifecycleMatches(PassengerSessionScope scope, String lifecycleStatus) {
        return (scope == NORMAL && "ACTIVE".equals(lifecycleStatus))
                || (scope == LIFECYCLE_RESTRICTED && "CANCELLING".equals(lifecycleStatus));
    }
}
