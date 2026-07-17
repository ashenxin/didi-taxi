package com.sx.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "wallet.payment.mock")
public class MockPaymentProperties {
    private boolean enabled = true;
    private String autoStatus = "SUCCESS";
    private List<String> allowedProfiles = new ArrayList<>(List.of("local", "dev", "test"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAutoStatus() { return autoStatus; }
    public void setAutoStatus(String autoStatus) { this.autoStatus = autoStatus; }
    public List<String> getAllowedProfiles() { return allowedProfiles; }
    public void setAllowedProfiles(List<String> allowedProfiles) { this.allowedProfiles = allowedProfiles; }
}
