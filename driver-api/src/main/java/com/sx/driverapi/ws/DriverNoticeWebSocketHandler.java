package com.sx.driverapi.ws;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;

/**
 * 司机长连接骨架：后续在此推送派单/订单状态（仅 WebSocket）。
 */
@Component
@Slf4j
public class DriverNoticeWebSocketHandler extends TextWebSocketHandler {

    private final DriverWsSessionRegistry registry;
    private final DriverWsProperties props;
    private final DriverAssignedPushService assignedPushService;
    private final CapacityDriverClient capacityDriverClient;

    public DriverNoticeWebSocketHandler(DriverWsSessionRegistry registry,
                                        DriverWsProperties props,
                                        DriverAssignedPushService assignedPushService,
                                        CapacityDriverClient capacityDriverClient) {
        this.registry = registry;
        this.props = props;
        this.assignedPushService = assignedPushService;
        this.capacityDriverClient = capacityDriverClient;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object idObj = session.getAttributes().get(DriverWsHandshakeInterceptor.ATTR_DRIVER_ID);
        if (!(idObj instanceof Number)) {
            registry.safeClose(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        long driverId = ((Number) idObj).longValue();
        registry.register(driverId, session);
        registry.get(driverId).touch();
        log.info("司机 WebSocket 已连接 driverId={} sessionId={} remote={}",
                driverId, session.getId(), session.getRemoteAddress());

        // 首次下发：当前指派列表（过渡期替代前端轮询）
        assignedPushService.pushAssignedIfChanged(driverId, true);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var ds = registry.getBySessionId(session.getId());
        if (ds == null) {
            return;
        }
        ds.touch();
        String payload = message == null ? null : message.getPayload();
        if (payload == null) {
            return;
        }
        String p = payload.trim();
        if (p.equalsIgnoreCase("ping") || p.equalsIgnoreCase("{\"type\":\"PING\"}") || p.equalsIgnoreCase("{\"type\": \"PING\"}")) {
            registry.safeSendText(session, "{\"type\":\"PONG\",\"ts\":" + System.currentTimeMillis() + "}");
            // 过渡期：每次心跳顺带推一次列表，避免调度未启用/未触发时不刷新
            assignedPushService.pushAssignedIfChanged(ds.getDriverId(), false);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var ds = registry.getBySessionId(session.getId());
        if (ds != null) {
            markOffline(ds.getDriverId(), "ws-closed");
        }
        registry.removeBySession(session);
    }

    @Scheduled(fixedDelayString = "${driver.ws.assigned-poll-interval-ms:2000}")
    public void scheduledPushAssigned() {
        for (var ds : registry.allSessions()) {
            if (ds == null) continue;
            assignedPushService.pushAssignedIfChanged(ds.getDriverId(), false);
        }
    }

    @Scheduled(fixedDelay = 5_000)
    public void scheduledHeartbeatSweep() {
        long now = System.currentTimeMillis();
        long timeout = props.getHeartbeatTimeoutMs();
        for (var ds : registry.allSessions()) {
            if (ds == null) continue;
            if (now - ds.lastSeenAtMs() > timeout) {
                log.info("WS heartbeat timeout driverId={} lastSeen={} now={}",
                        ds.getDriverId(), Instant.ofEpochMilli(ds.lastSeenAtMs()), Instant.ofEpochMilli(now));
                markOffline(ds.getDriverId(), "heartbeat-timeout");
                registry.safeClose(ds.getSession(), CloseStatus.SESSION_NOT_RELIABLE);
                registry.removeBySession(ds.getSession());
            }
        }
    }

    private void markOffline(long driverId, String reason) {
        try {
            DriverOnlineBody body = new DriverOnlineBody();
            body.setOnline(false);
            capacityDriverClient.setOnline(driverId, body);
        } catch (Exception e) {
            log.debug("presence offline call ignored driverId={} err={}", driverId, e.toString());
        }
        log.info("presence offline driverId={} reason={}", driverId, reason);
    }
}
