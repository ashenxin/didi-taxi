package com.sx.passengerapi.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerNoticeWebSocketHandlerTest {

    @Test
    void connectionEstablishedAfterLogoutIsRejectedByCapturedGeneration() throws Exception {
        PassengerWsSessionRegistry registry = new PassengerWsSessionRegistry();
        var permit = registry.captureRegistration(7L);
        registry.closeCustomerSessions(7L, "logout");
        WebSocketSession session = session("late-session", permit);
        PassengerNoticeWebSocketHandler handler = new PassengerNoticeWebSocketHandler(
                registry, new PassengerWsProperties());

        handler.afterConnectionEstablished(session);

        assertThat(registry.get(7L)).isNull();
        verify(session).close(new CloseStatus(4001, "auth_epoch_changed"));
        handler.stopWsMaintenance();
    }

    @Test
    void closeImmediatelyAfterRegistrationDoesNotCrashConnectionCallback() {
        PassengerWsSessionRegistry registry = mock(PassengerWsSessionRegistry.class);
        PassengerWsSessionRegistry.RegistrationPermit permit = mock(
                PassengerWsSessionRegistry.RegistrationPermit.class);
        WebSocketSession session = session("racing-session", permit);
        when(registry.register(permit, session)).thenReturn(true);
        when(registry.get(7L)).thenReturn(null);
        PassengerNoticeWebSocketHandler handler = new PassengerNoticeWebSocketHandler(
                registry, new PassengerWsProperties());

        assertThatCode(() -> handler.afterConnectionEstablished(session)).doesNotThrowAnyException();

        handler.stopWsMaintenance();
    }

    private static WebSocketSession session(
            String id, PassengerWsSessionRegistry.RegistrationPermit permit) {
        WebSocketSession session = mock(WebSocketSession.class);
        var attributes = new HashMap<String, Object>();
        attributes.put(PassengerWsHandshakeInterceptor.ATTR_CUSTOMER_ID, 7L);
        attributes.put(PassengerWsHandshakeInterceptor.ATTR_REGISTRATION_PERMIT, permit);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
