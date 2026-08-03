package com.sx.passengerapi.controller;

import com.sx.passengerapi.auth.InvalidPassengerSessionException;
import com.sx.passengerapi.auth.ParsedPassengerJwt;
import com.sx.passengerapi.auth.PassengerSessionScope;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.config.PassengerInternalClientProperties;
import com.sx.passengerapi.model.auth.WsTicketPrecheckRequest;
import com.sx.passengerapi.ws.PassengerWsTicketValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerInternalWsPrecheckControllerTest {
    private final PassengerWsTicketValidator validator = mock(PassengerWsTicketValidator.class);
    private final PassengerInternalClientProperties properties = new PassengerInternalClientProperties();
    private PassengerInternalWsPrecheckController controller;

    @BeforeEach
    void setUp() {
        properties.setToken("internal-token-value");
        controller = new PassengerInternalWsPrecheckController(validator, properties);
    }

    @Test
    void validInternalIdentityAndTicketReturns200() {
        ParsedPassengerJwt parsed = token();
        when(validator.parse("ws-ticket")).thenReturn(parsed);
        when(validator.validate(parsed)).thenReturn(parsed);

        var response = controller.precheck(
                "internal-token-value", new WsTicketPrecheckRequest("ws-ticket"));

        assertThat(response.getCode()).isEqualTo(200);
        verify(validator).validate(parsed);
    }

    @Test
    void wrongInternalIdentityIsRejectedBeforeTicketParsing() {
        assertThatThrownBy(() -> controller.precheck(
                "wrong-token", new WsTicketPrecheckRequest("ws-ticket")))
                .isInstanceOfSatisfying(BizErrorException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(401));
    }

    @Test
    void staleTicketIsMappedTo401() {
        ParsedPassengerJwt parsed = token();
        when(validator.parse("stale-ticket")).thenReturn(parsed);
        when(validator.validate(parsed)).thenThrow(new InvalidPassengerSessionException());

        assertThatThrownBy(() -> controller.precheck(
                "internal-token-value", new WsTicketPrecheckRequest("stale-ticket")))
                .isInstanceOfSatisfying(BizErrorException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(401));
    }

    private static ParsedPassengerJwt token() {
        return new ParsedPassengerJwt(7L, "", 3L, PassengerSessionScope.NORMAL, 2, null);
    }
}
