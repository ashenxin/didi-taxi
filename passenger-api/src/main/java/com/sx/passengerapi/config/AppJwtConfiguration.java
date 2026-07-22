package com.sx.passengerapi.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AppJwtProperties.class,
        CouponClaimIdentityProperties.class,
        PassengerInternalClientProperties.class,
        OrderLifecycleInternalClientProperties.class
})
public class AppJwtConfiguration {
}
