package com.sx.passengerapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 乘客端 JWT：签发密钥须与网关 {@code gateway.jwt.secret-app} 一致；
 * {@code audience} 须与 {@code gateway.jwt.audience-app} 一致以便开启 aud 校验。
 */
@ConfigurationProperties(prefix = "app.jwt")
public class AppJwtProperties {

    private String secret = "";

    private long expirationSeconds = 604800L;

    /** 与 gateway.application.yml gateway.jwt.audience-app 一致 */
    private String audience = "app-bff";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public void setExpirationSeconds(long expirationSeconds) {
        this.expirationSeconds = expirationSeconds;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }
}
