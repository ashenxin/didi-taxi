package com.sx.driverapi.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 司机长连接骨架：后续在此推送派单/订单状态（仅 WebSocket）。
 */
@Component
@Slf4j
public class DriverNoticeWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("司机 WebSocket 已连接 sessionId={} remote={}", session.getId(), session.getRemoteAddress());
    }
}
