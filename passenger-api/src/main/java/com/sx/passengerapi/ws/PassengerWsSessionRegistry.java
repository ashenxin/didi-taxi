package com.sx.passengerapi.ws;

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
public class PassengerWsSessionRegistry {

    @Getter
    public static class PassengerSession {
        private final long customerId;
        private final WebSocketSession session;
        private final AtomicLong lastSeenAtMs = new AtomicLong(System.currentTimeMillis());

        public PassengerSession(long customerId, WebSocketSession session) {
            this.customerId = customerId;
            this.session = session;
        }

        public void touch() {
            lastSeenAtMs.set(System.currentTimeMillis());
        }

        public long lastSeenAtMs() {
            return lastSeenAtMs.get();
        }
    }

    private final Map<Long, PassengerSession> byCustomerId = new ConcurrentHashMap<>();
    private final Map<String, Long> customerIdBySessionId = new ConcurrentHashMap<>();

    /**
     * 注册会话；若同乘客已有连接则关闭旧连接（单端在线）。
     */
    public void register(long customerId, WebSocketSession session) {
        if (customerId <= 0 || session == null) {
            return;
        }
        PassengerSession prev = byCustomerId.get(customerId);
        if (prev != null && prev.getSession() != null && prev.getSession().isOpen()
                && !prev.getSession().getId().equals(session.getId())) {
            customerIdBySessionId.remove(prev.getSession().getId());
            safeClose(prev.getSession(), new CloseStatus(4000, "replaced"));
            log.info("WS replaced previous session customerId={} oldSessionId={}", customerId, prev.getSession().getId());
        }
        PassengerSession ps = new PassengerSession(customerId, session);
        byCustomerId.put(customerId, ps);
        customerIdBySessionId.put(session.getId(), customerId);
        log.info("WS session registered customerId={} sessionId={}", customerId, session.getId());
    }

    public PassengerSession get(long customerId) {
        return byCustomerId.get(customerId);
    }

    public PassengerSession getBySessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Long customerId = customerIdBySessionId.get(sessionId);
        return customerId == null ? null : byCustomerId.get(customerId);
    }

    public Collection<PassengerSession> allSessions() {
        return byCustomerId.values();
    }

    public void removeBySession(WebSocketSession session) {
        if (session == null) {
            return;
        }
        Long customerId = customerIdBySessionId.remove(session.getId());
        if (customerId != null) {
            PassengerSession cur = byCustomerId.get(customerId);
            if (cur != null && session.getId().equals(cur.getSession().getId())) {
                byCustomerId.remove(customerId);
                log.info("WS session removed customerId={} sessionId={}", customerId, session.getId());
            }
        }
    }

    public void safeClose(WebSocketSession session, CloseStatus status) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(status == null ? CloseStatus.NORMAL : status);
            }
        } catch (IOException e) {
            log.debug("WS close ignored sessionId={} err={}", session.getId(), e.toString());
        }
    }

    public void safeSendText(WebSocketSession session, String text) {
        if (session == null || text == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException e) {
            log.debug("WS send ignored sessionId={} err={}", session.getId(), e.toString());
        }
    }
}
