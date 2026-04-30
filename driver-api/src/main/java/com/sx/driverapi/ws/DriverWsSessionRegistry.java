package com.sx.driverapi.ws;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class DriverWsSessionRegistry {

    @Getter
    public static class DriverSession {
        private final long driverId;
        private final WebSocketSession session;
        private final AtomicLong lastSeenAtMs = new AtomicLong(System.currentTimeMillis());
        private volatile String lastAssignedHash = "";

        public DriverSession(long driverId, WebSocketSession session) {
            this.driverId = driverId;
            this.session = session;
        }

        public void touch() {
            lastSeenAtMs.set(System.currentTimeMillis());
        }

        public long lastSeenAtMs() {
            return lastSeenAtMs.get();
        }

        public String getLastAssignedHash() {
            return lastAssignedHash;
        }

        public void setLastAssignedHash(String lastAssignedHash) {
            this.lastAssignedHash = lastAssignedHash == null ? "" : lastAssignedHash;
        }
    }

    private final Map<Long, DriverSession> byDriverId = new ConcurrentHashMap<>();
    private final Map<String, Long> driverIdBySessionId = new ConcurrentHashMap<>();

    public void register(long driverId, WebSocketSession session) {
        if (driverId <= 0 || session == null) {
            return;
        }
        DriverSession ds = new DriverSession(driverId, session);
        byDriverId.put(driverId, ds);
        driverIdBySessionId.put(session.getId(), driverId);
        log.info("WS session registered driverId={} sessionId={}", driverId, session.getId());
    }

    public DriverSession get(long driverId) {
        return byDriverId.get(driverId);
    }

    public DriverSession getBySessionId(String sessionId) {
        if (sessionId == null) return null;
        Long driverId = driverIdBySessionId.get(sessionId);
        return driverId == null ? null : byDriverId.get(driverId);
    }

    public Collection<DriverSession> allSessions() {
        return byDriverId.values();
    }

    public void removeBySession(WebSocketSession session) {
        if (session == null) return;
        Long driverId = driverIdBySessionId.remove(session.getId());
        if (driverId != null) {
            byDriverId.remove(driverId);
            log.info("WS session removed driverId={} sessionId={}", driverId, session.getId());
        }
    }

    public void safeClose(WebSocketSession session, CloseStatus status) {
        if (session == null) return;
        try {
            if (session.isOpen()) {
                session.close(status == null ? CloseStatus.NORMAL : status);
            }
        } catch (IOException e) {
            log.debug("WS close ignored sessionId={} err={}", session.getId(), e.toString());
        }
    }

    public void safeSendText(WebSocketSession session, String text) {
        if (session == null || text == null) return;
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException e) {
            log.debug("WS send ignored sessionId={} err={}", session.getId(), e.toString());
        }
    }
}

