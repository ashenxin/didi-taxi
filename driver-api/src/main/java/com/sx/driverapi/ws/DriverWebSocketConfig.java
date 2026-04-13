package com.sx.driverapi.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class DriverWebSocketConfig implements WebSocketConfigurer {

    private final DriverNoticeWebSocketHandler noticeWebSocketHandler;

    public DriverWebSocketConfig(DriverNoticeWebSocketHandler noticeWebSocketHandler) {
        this.noticeWebSocketHandler = noticeWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(noticeWebSocketHandler, "/driver/ws/v1/stream")
                .setAllowedOrigins("*");
    }
}
