package com.sx.driverapi.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.jwt")
public class DriverJwtProperties {
    private String secret;
    /** 默认 8 小时，与 {@code application.yml app.jwt.expiration-seconds} 一致 */
    private long expirationSeconds = 28800;
    private String audience = "driver-bff";
}

