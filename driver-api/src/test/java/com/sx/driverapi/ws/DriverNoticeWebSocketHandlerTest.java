package com.sx.driverapi.ws;

import com.sx.driverapi.service.DriverBffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverNoticeWebSocketHandlerTest {

    private DriverWsSessionRegistry registry;
    private DriverAssignedPushService assignedPushService;
    private DriverBffService driverBffService;
    private DriverNoticeWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        registry = new DriverWsSessionRegistry();
        assignedPushService = mock(DriverAssignedPushService.class);
        driverBffService = mock(DriverBffService.class);
        DriverWsProperties props = new DriverWsProperties();
        props.setHeartbeatTimeoutMs(45_000);
        handler = new DriverNoticeWebSocketHandler(registry, props, assignedPushService, driverBffService);
    }

    @Test
    void connectionEstablishedRegistersAndPushesAssignedList() {
        WebSocketSession session = session("s1", 80001L);

        handler.afterConnectionEstablished(session);

        verify(assignedPushService).pushAssignedIfChanged(80001L, true);
    }

    @Test
    void pingRepliesPongAndRefreshesAssignedList() throws Exception {
        WebSocketSession session = session("s1", 80001L);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("ping"));

        verify(session).sendMessage(any(TextMessage.class));
        verify(assignedPushService).pushAssignedIfChanged(80001L, false);
    }

    @Test
    void connectionClosedMarksDriverOffline() {
        WebSocketSession session = session("s1", 80001L);
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(driverBffService).setOnline(80001L, false, null, null);
    }

    @Test
    void heartbeatTimeoutMarksDriverOfflineAndClosesSession() throws Exception {
        WebSocketSession session = session("s1", 80001L);
        handler.afterConnectionEstablished(session);
        registry.get(80001L).getLastSeenAtMs().set(System.currentTimeMillis() - 60_000);

        handler.scheduledHeartbeatSweep();

        verify(driverBffService).setOnline(80001L, false, null, null);
        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void secondConnectionReplacesOldSessionWithoutMarkingOffline() throws Exception {
        WebSocketSession oldSession = session("old", 80001L);
        WebSocketSession newSession = session("new", 80001L);

        handler.afterConnectionEstablished(oldSession);
        handler.afterConnectionEstablished(newSession);
        handler.afterConnectionClosed(oldSession, CloseStatus.NORMAL);

        verify(oldSession).close(CloseStatus.NORMAL.withReason("replaced-by-new-driver-ws"));
        verify(driverBffService, never()).setOnline(eq(80001L), anyBoolean(), eq(null), eq(null));
        verify(assignedPushService, times(2)).pushAssignedIfChanged(80001L, true);
    }

    private static WebSocketSession session(String id, long driverId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        HashMap<String, Object> attrs = new HashMap<>();
        attrs.put(DriverWsHandshakeInterceptor.ATTR_DRIVER_ID, driverId);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }
}
