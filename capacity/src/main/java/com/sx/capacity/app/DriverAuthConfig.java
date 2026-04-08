package com.sx.capacity.app;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DriverAuthProperties.class)
public class DriverAuthConfig {
}

