package com.sx.passengerapi.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerWsSessionRegistryTest {

    @Test
    void registrationCapturedBeforeCloseIsRejectedAfterClose() throws Exception {
        PassengerWsSessionRegistry registry = new PassengerWsSessionRegistry();
        var permit = registry.captureRegistration(7L);
        WebSocketSession late = openSession("late-session");

        registry.closeCustomerSessions(7L, "logout");
        boolean accepted = registry.register(permit, late);

        assertThat(accepted).isFalse();
        assertThat(registry.get(7L)).isNull();
        assertThat(registry.getBySessionId("late-session")).isNull();
        verify(late).close(new CloseStatus(4001, "auth_epoch_changed"));
    }

    @Test
    void registrationCompletedBeforeCloseIsRemovedAndClosed() throws Exception {
        PassengerWsSessionRegistry registry = new PassengerWsSessionRegistry();
        WebSocketSession session = openSession("session-1");
        assertThat(registry.register(registry.captureRegistration(7L), session)).isTrue();

        registry.closeCustomerSessions(7L, "logout");

        assertThat(registry.get(7L)).isNull();
        assertThat(registry.getBySessionId("session-1")).isNull();
        verify(session).close(new CloseStatus(4001, "logout"));
    }

    @Test
    void concurrentDoubleRegistrationLeavesOnlyWinnerAndNoReverseMappingForLoser() throws Exception {
        PassengerWsSessionRegistry registry = new PassengerWsSessionRegistry();
        var firstPermit = registry.captureRegistration(7L);
        var secondPermit = registry.captureRegistration(7L);
        WebSocketSession first = openSession("session-1");
        WebSocketSession second = openSession("session-2");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> {
                start.await();
                return registry.register(firstPermit, first);
            });
            Future<Boolean> secondResult = executor.submit(() -> {
                start.await();
                return registry.register(secondPermit, second);
            });
            start.countDown();
            assertThat(firstResult.get()).isTrue();
            assertThat(secondResult.get()).isTrue();
        }

        PassengerWsSessionRegistry.PassengerSession current = registry.get(7L);
        assertThat(current).isNotNull();
        WebSocketSession winner = current.getSession();
        WebSocketSession loser = winner == first ? second : first;
        assertThat(registry.getBySessionId(winner.getId())).isSameAs(current);
        assertThat(registry.getBySessionId(loser.getId())).isNull();
        verify(loser).close(new CloseStatus(4000, "replaced"));
    }

    @Test
    void unknownOrSensitiveReasonIsSanitized() throws Exception {
        PassengerWsSessionRegistry registry = new PassengerWsSessionRegistry();
        WebSocketSession session = openSession("session-2");
        registry.register(registry.captureRegistration(7L), session);

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
