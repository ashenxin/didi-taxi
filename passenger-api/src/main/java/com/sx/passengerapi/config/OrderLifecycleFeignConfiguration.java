package com.sx.passengerapi.config;

import feign.RequestInterceptor;
import feign.Request;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/** 仅由 Order 生命周期 Feign client 显式引用，避免注销凭据污染普通订单请求。 */
public class OrderLifecycleFeignConfiguration {
    @Bean
    public RequestInterceptor orderLifecycleToken(OrderLifecycleInternalClientProperties properties) {
        return template -> template.header("X-Internal-Token", properties.getToken());
    }

    @Bean
    public Request.Options orderLifecycleRequestOptions(
            OrderLifecycleInternalClientProperties properties) {
        if (properties.getConnectTimeoutMs() <= 0 || properties.getReadTimeoutMs() <= 0) {
            throw new IllegalStateException("Order生命周期影子调用超时必须为正数");
        }
        return new Request.Options(Duration.ofMillis(properties.getConnectTimeoutMs()),
                Duration.ofMillis(properties.getReadTimeoutMs()), false);
    }
}
