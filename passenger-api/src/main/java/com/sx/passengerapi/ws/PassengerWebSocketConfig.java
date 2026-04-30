package com.sx.passengerapi.ws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(PassengerWsProperties.class)
public class PassengerWebSocketConfig implements WebSocketConfigurer {

    private final PassengerNoticeWebSocketHandler noticeWebSocketHandler;
    private final PassengerWsHandshakeInterceptor handshakeInterceptor;

    public PassengerWebSocketConfig(PassengerNoticeWebSocketHandler noticeWebSocketHandler,
                                    PassengerWsHandshakeInterceptor handshakeInterceptor) {
        this.noticeWebSocketHandler = noticeWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var reg = registry.addHandler(noticeWebSocketHandler, "/app/ws/v1/stream")
                .setAllowedOrigins("*");
        if (handshakeInterceptor != null) {
            reg.addInterceptors(handshakeInterceptor);
        }
    }
}
