package com.sx.driverapi.ws;

import com.sx.driverapi.service.DriverBffService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 司机长连接骨架：后续在此推送派单/订单状态（仅 WebSocket）。
 */
@Component
@Slf4j
public class DriverNoticeWebSocketHandler extends TextWebSocketHandler {

    private final DriverWsSessionRegistry registry;
    private final DriverWsProperties props;
    private final DriverAssignedPushService assignedPushService;
    private final DriverBffService driverBffService;
    private final ScheduledExecutorService wsMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "driver-ws-maintenance");
        t.setDaemon(true);
        return t;
    });

    public DriverNoticeWebSocketHandler(DriverWsSessionRegistry registry,
                                        DriverWsProperties props,
                                        DriverAssignedPushService assignedPushService,
                                        DriverBffService driverBffService) {
        this.registry = registry;
        this.props = props;
        this.assignedPushService = assignedPushService;
        this.driverBffService = driverBffService;
    }

    @PostConstruct
    public void startWsMaintenance() {
        wsMaintenanceExecutor.scheduleWithFixedDelay(
                this::safeScheduledPushAssigned,
                props.getAssignedPollIntervalMs(),
                props.getAssignedPollIntervalMs(),
                TimeUnit.MILLISECONDS
        );
        wsMaintenanceExecutor.scheduleWithFixedDelay(
                this::safeHeartbeatSweep,
                5_000,
                5_000,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    public void stopWsMaintenance() {
        wsMaintenanceExecutor.shutdownNow();
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
        var ds = registry.removeBySession(session);
        if (ds != null) {
            markPresenceDisconnected(ds.getDriverId(), "ws-closed");
        }
    }

    public void scheduledPushAssigned() {
        for (var ds : registry.allSessions()) {
            if (ds == null) continue;
            assignedPushService.pushAssignedIfChanged(ds.getDriverId(), false);
        }
    }

    public void scheduledHeartbeatSweep() {
        long now = System.currentTimeMillis();
        long timeout = props.getHeartbeatTimeoutMs();
        for (var ds : registry.allSessions()) {
            if (ds == null) continue;
            if (now - ds.lastSeenAtMs() > timeout) {
                log.info("WS heartbeat timeout driverId={} lastSeen={} now={}",
                        ds.getDriverId(), Instant.ofEpochMilli(ds.lastSeenAtMs()), Instant.ofEpochMilli(now));
                var removed = registry.removeBySession(ds.getSession());
                if (removed != null) {
                    markPresenceDisconnected(removed.getDriverId(), "heartbeat-timeout");
                    registry.safeClose(removed.getSession(), CloseStatus.SESSION_NOT_RELIABLE);
                }
            }
        }
    }

    private void markPresenceDisconnected(long driverId, String reason) {
        log.info("driver ws disconnected driverId={} reason={}", driverId, reason);
        try {
            driverBffService.setOnline(driverId, false, null, null);
        } catch (Exception e) {
            log.warn("driver ws offline sync failed driverId={} reason={} err={}",
                    driverId, reason, e.toString());
        }
    }

    private void safeScheduledPushAssigned() {
        try {
            scheduledPushAssigned();
        } catch (Exception e) {
            log.warn("driver ws assigned push failed: {}", e.toString());
        }
    }

    private void safeHeartbeatSweep() {
        try {
            scheduledHeartbeatSweep();
        } catch (Exception e) {
            log.warn("driver ws heartbeat sweep failed: {}", e.toString());
        }
    }
}
