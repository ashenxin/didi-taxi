package com.sx.passengerapi.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassengerAuthStateContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PassengerAuthDecisionService decisions = new PassengerAuthDecisionService();

    @Test
    void bffDtoReadsAllSharedContractCasesWithStableFieldTypes() throws Exception {
        JsonNode cases = fixture().path("cases");
        assertThat(cases).hasSize(3);
        for (JsonNode contractCase : cases) {
            JsonNode response = contractCase.path("response");
            InternalAuthStateResponse state = mapper.treeToValue(response, InternalAuthStateResponse.class);
            assertThat(state.getCustomerId()).isEqualTo(response.path("customerId").longValue());
            assertThat(response.path("authEpoch").isIntegralNumber()).isTrue();
            assertThat(response.path("allowed").isBoolean()).isTrue();
            assertThat(mapper.<JsonNode>valueToTree(state).toString()).isEqualTo(response.toString());
        }
    }

    @Test
    void newerLoginEpochRejectsBothOldHttpAndOldWsTokens() throws Exception {
        InternalAuthStateResponse active = state("ACTIVE");
        long epochN = active.getAuthEpoch();
        assertThat(decisions.verify(token(epochN, NORMAL, 1, null), active, 1).authEpoch()).isEqualTo(epochN);

        active.setAuthEpoch(epochN + 1);
        assertThatThrownBy(() -> decisions.verify(token(epochN, NORMAL, 1, null), active, 1))
                .isInstanceOf(InvalidPassengerSessionException.class);
        assertThatThrownBy(() -> decisions.verify(token(epochN, NORMAL, 2, null), active, 2))
                .isInstanceOf(InvalidPassengerSessionException.class);
    }

    @Test
    void cancellingContractAcceptsOnlyItsBoundRestrictedHttpIdentity() throws Exception {
        InternalAuthStateResponse cancelling = state("CANCELLING");
        assertThat(decisions.verify(token(cancelling.getAuthEpoch(), LIFECYCLE_RESTRICTED, 1,
                cancelling.getCurrentLifecycleOperationNo()), cancelling, 1).scope())
                .isEqualTo(LIFECYCLE_RESTRICTED);
        assertThatThrownBy(() -> decisions.verify(token(cancelling.getAuthEpoch(), LIFECYCLE_RESTRICTED, 2,
                cancelling.getCurrentLifecycleOperationNo()), cancelling, 1))
                .isInstanceOf(InvalidPassengerSessionException.class);
    }

    @Test
    void authenticationDecisionPathHasNoRedisDependency() {
        assertThat(Arrays.stream(new Class<?>[]{PassengerJwtAuthFilter.class, PassengerAuthDecisionService.class,
                        com.sx.passengerapi.ws.PassengerWsHandshakeInterceptor.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType)
                .map(Class::getName))
                .noneMatch(name -> name.toLowerCase().contains("redis"));
    }

    private JsonNode fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/contracts/passenger-auth-state-v1.json")) {
            assertThat(input).as("共享认证状态契约必须位于 classpath:/contracts").isNotNull();
            return mapper.readTree(input);
        }
    }

    private InternalAuthStateResponse state(String name) throws Exception {
        for (JsonNode contractCase : fixture().path("cases")) {
            if (name.equals(contractCase.path("name").asText())) {
                return mapper.treeToValue(contractCase.path("response"), InternalAuthStateResponse.class);
            }
        }
        throw new AssertionError("missing contract case " + name);
    }

    private static ParsedPassengerJwt token(long epoch, PassengerSessionScope scope, int audit, String operationNo) {
        return new ParsedPassengerJwt(7L, "", epoch, scope, audit, operationNo);
    }
}
