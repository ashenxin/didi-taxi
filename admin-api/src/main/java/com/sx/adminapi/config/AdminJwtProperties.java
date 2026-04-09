package com.sx.adminapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin.jwt")
public class AdminJwtProperties {

    private String secret = "";

    private long expirationSeconds = 43200L;

    /**
     * 与网关 {@code gateway.jwt.audience-admin} 保持一致（开启 aud 校验时必填）。
     */
    private String audience = "admin-bff";

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
