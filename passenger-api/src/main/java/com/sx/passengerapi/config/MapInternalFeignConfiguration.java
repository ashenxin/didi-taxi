package com.sx.passengerapi.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/** 仅由 map 内部接口 Feign client 显式引用，避免内部凭据污染其他下游。 */
public class MapInternalFeignConfiguration {

    @Bean
    public RequestInterceptor mapInternalToken(MapInternalClientProperties properties) {
        return template -> template.header("X-Internal-Service-Token", properties.getToken());
    }
}
