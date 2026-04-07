package com.sx.passenger.app;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppCustomerAuthProperties.class)
public class AppCustomerAuthConfig {
}
