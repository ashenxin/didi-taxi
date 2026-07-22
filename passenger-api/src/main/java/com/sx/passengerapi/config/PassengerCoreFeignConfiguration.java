package com.sx.passengerapi.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/** 仅由 passenger core Feign client 显式引用，避免内部凭据污染其他下游。 */
public class PassengerCoreFeignConfiguration {

    @Bean
    public RequestInterceptor passengerInternalToken(PassengerInternalClientProperties properties) {
        return template -> template.header("X-Internal-Service-Token", properties.getToken());
    }
}
