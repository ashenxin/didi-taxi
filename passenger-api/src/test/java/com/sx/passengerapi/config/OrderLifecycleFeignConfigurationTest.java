package com.sx.passengerapi.config;

import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.OrderLifecycleClient;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLifecycleFeignConfigurationTest {
    @Test
    void interceptorAddsIndependentTokenWithoutLeakingItFromToString() {
        OrderLifecycleInternalClientProperties properties = new OrderLifecycleInternalClientProperties();
        properties.setToken("test-order-lifecycle-internal-token");
        RequestInterceptor interceptor = new OrderLifecycleFeignConfiguration().orderLifecycleToken(properties);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("X-Internal-Token"))
                .containsExactly("test-order-lifecycle-internal-token");
        assertThat(interceptor.toString()).doesNotContain("test-order-lifecycle-internal-token");
    }

    @Test
    void lifecycleConfigurationIsScopedOnlyToLifecycleClient() {
        FeignClient lifecycle = OrderLifecycleClient.class.getAnnotation(FeignClient.class);
        FeignClient ordinary = OrderClient.class.getAnnotation(FeignClient.class);

        assertThat(lifecycle.configuration()).containsExactly(OrderLifecycleFeignConfiguration.class);
        assertThat(ordinary.configuration()).doesNotContain(OrderLifecycleFeignConfiguration.class);
    }

    @Test
    void lifecycleShadowUsesDedicatedShortTimeouts() {
        OrderLifecycleInternalClientProperties properties = new OrderLifecycleInternalClientProperties();
        properties.setConnectTimeoutMs(250);
        properties.setReadTimeoutMs(700);

        Request.Options options = new OrderLifecycleFeignConfiguration()
                .orderLifecycleRequestOptions(properties);

        assertThat(options.connectTimeoutMillis()).isEqualTo(250);
        assertThat(options.readTimeoutMillis()).isEqualTo(700);
    }
}
