package com.sx.passengerapi.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppJwtProperties.class)
public class AppJwtConfiguration {
}
