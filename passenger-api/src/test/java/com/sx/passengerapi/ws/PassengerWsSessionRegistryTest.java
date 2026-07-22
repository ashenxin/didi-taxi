package com.sx.passengerapi.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerWsSessionRegistryTest {

    @Test
    void closesAndRemovesCustomerSessionWithAllowedReason() throws Exception {
        PassengerWsSessionRegistry registry = new PassengerWsSessionRegistry();
        WebSocketSession session = openSession("session-1");
        registry.register(7L, session);

        registry.closeCustomerSessions(7L, "logout");

        assertThat(registry.get(7L)).isNull();
        assertThat(registry.getBySessionId("session-1")).isNull();
        verify(session).close(new CloseStatus(4001, "logout"));
    }

    @Test
    void unknownOrSensitiveReasonIsSanitized() throws Exception {
        PassengerWsSessionRegistry registry = new PassengerWsSessionRegistry();
        WebSocketSession session = openSession("session-2");
        registry.register(7L, session);

        registry.closeCustomerSessions(7L, "token=secret phone=13800138000");

        verify(session).close(new CloseStatus(4001, "auth_epoch_changed"));
    }

    private static WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
