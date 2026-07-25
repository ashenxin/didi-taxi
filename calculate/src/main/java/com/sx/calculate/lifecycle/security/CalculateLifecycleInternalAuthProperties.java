package com.sx.calculate.lifecycle.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "calculate.lifecycle.internal-auth")
public class CalculateLifecycleInternalAuthProperties {
    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
