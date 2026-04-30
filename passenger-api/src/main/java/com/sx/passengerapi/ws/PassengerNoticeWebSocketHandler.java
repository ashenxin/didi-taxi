package com.sx.passengerapi.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;

/**
 * 乘客订单通知通道：握手后仅维护心跳；业务推送见 {@link PassengerWsNotifyService}。
 */
@Component
@Slf4j
public class PassengerNoticeWebSocketHandler extends TextWebSocketHandler {

    private final PassengerWsSessionRegistry registry;
    private final PassengerWsProperties props;

    public PassengerNoticeWebSocketHandler(PassengerWsSessionRegistry registry, PassengerWsProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object idObj = session.getAttributes().get(PassengerWsHandshakeInterceptor.ATTR_CUSTOMER_ID);
        if (!(idObj instanceof Number)) {
            registry.safeClose(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        long customerId = ((Number) idObj).longValue();
        registry.register(customerId, session);
        registry.get(customerId).touch();
        log.info("乘客 WebSocket 已连接 customerId={} sessionId={} remote={}",
                customerId, session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var ps = registry.getBySessionId(session.getId());
        if (ps == null) {
            return;
        }
        ps.touch();
        String payload = message == null ? null : message.getPayload();
        if (payload == null) {
            return;
        }
        String p = payload.trim();
        if (p.equalsIgnoreCase("ping")
                || "{\"type\":\"PING\"}".equalsIgnoreCase(p)
                || "{\"type\": \"PING\"}".equalsIgnoreCase(p)) {
            registry.safeSendText(session, "{\"type\":\"PONG\",\"ts\":" + System.currentTimeMillis() + "}");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.removeBySession(session);
    }

    @Scheduled(fixedDelay = 5000)
    public void heartbeatSweep() {
        if (!props.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long timeout = props.getHeartbeatTimeoutMs();
        for (PassengerWsSessionRegistry.PassengerSession ps : registry.allSessions()) {
            if (ps == null) {
                continue;
            }
            if (now - ps.lastSeenAtMs() > timeout) {
                log.info("WS heartbeat timeout customerId={} lastSeen={}",
                        ps.getCustomerId(), Instant.ofEpochMilli(ps.lastSeenAtMs()));
                registry.safeClose(ps.getSession(), CloseStatus.SESSION_NOT_RELIABLE);
                registry.removeBySession(ps.getSession());
            }
        }
    }
}
