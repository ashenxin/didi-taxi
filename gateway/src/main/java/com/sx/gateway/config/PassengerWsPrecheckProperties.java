package com.sx.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Passenger WebSocket 在网关 Upgrade 前的内部预检配置。 */
@ConfigurationProperties(prefix = "gateway.passenger-ws-precheck")
public class PassengerWsPrecheckProperties {
    private boolean enabled = true;
    private String serviceBaseUrl = "http://passenger-api";
    private String internalToken = "";
    private long timeoutMillis = 2000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServiceBaseUrl() { return serviceBaseUrl; }
    public void setServiceBaseUrl(String serviceBaseUrl) { this.serviceBaseUrl = serviceBaseUrl; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }
}
