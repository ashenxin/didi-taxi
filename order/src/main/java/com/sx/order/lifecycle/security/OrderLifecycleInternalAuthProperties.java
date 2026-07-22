package com.sx.order.lifecycle.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.lifecycle.internal-auth")
public class OrderLifecycleInternalAuthProperties {
    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
