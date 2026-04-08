package com.sx.driverapi.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DriverJwtProperties.class)
public class DriverJwtConfiguration {
}

