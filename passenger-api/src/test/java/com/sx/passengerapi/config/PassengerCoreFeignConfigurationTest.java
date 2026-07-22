package com.sx.passengerapi.config;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.MapClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.PassengerCoreAuthClient;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.PassengerCoreSettingsClient;
import com.sx.passengerapi.client.WalletClient;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassengerCoreFeignConfigurationTest {

    @Test
    void addsInternalTokenHeaderWithoutExposingItsValueElsewhere() {
        PassengerInternalClientProperties properties = new PassengerInternalClientProperties();
        properties.setToken("test-internal-token");
        RequestInterceptor interceptor = new PassengerCoreFeignConfiguration()
                .passengerInternalToken(properties);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("X-Internal-Service-Token"))
                .containsExactly("test-internal-token");
        assertThat(interceptor.toString()).doesNotContain("test-internal-token");
    }

    @Test
    void configurationIsScopedToExactlyTheThreePassengerCoreClients() {
        for (Class<?> coreClient : List.of(
                PassengerCoreAuthClient.class,
                PassengerCoreSettingsClient.class,
                PassengerCoreAuthStateClient.class)) {
            assertThat(coreClient.getAnnotation(FeignClient.class).configuration())
                    .containsExactly(PassengerCoreFeignConfiguration.class);
        }

        for (Class<?> otherClient : List.of(
                OrderClient.class,
                CalculateClient.class,
                WalletClient.class,
                MapClient.class)) {
            assertThat(otherClient.getAnnotation(FeignClient.class).configuration())
                    .doesNotContain(PassengerCoreFeignConfiguration.class);
        }
    }
}
