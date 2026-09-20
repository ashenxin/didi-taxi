package com.sx.passengerapi.config;

import feign.RequestInterceptor;
import feign.Request;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/** 仅由 map 内部接口 Feign client 显式引用，避免内部凭据污染其他下游。 */
public class MapInternalFeignConfiguration {

    @Bean
    public RequestInterceptor mapInternalToken(MapInternalClientProperties properties) {
        return template -> template.header("X-Internal-Service-Token", properties.getToken());
    }

    @Bean
    public Request.Options mapInternalRequestOptions(MapInternalClientProperties properties) {
        if (properties.getConnectTimeoutMs() <= 0 || properties.getReadTimeoutMs() <= 0) {
            throw new IllegalStateException("Map内部调用超时必须为正数");
        }
        return new Request.Options(Duration.ofMillis(properties.getConnectTimeoutMs()),
                Duration.ofMillis(properties.getReadTimeoutMs()), false);
    }
}
