package com.sx.driverapi.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.jwt")
public class DriverJwtProperties {
    private String secret;
    private long expirationSeconds = 604800;
    private String audience = "driver-bff";
}

