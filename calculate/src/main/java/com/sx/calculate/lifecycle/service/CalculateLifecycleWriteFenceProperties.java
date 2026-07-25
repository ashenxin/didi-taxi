package com.sx.calculate.lifecycle.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "calculate.lifecycle.write-fence")
public class CalculateLifecycleWriteFenceProperties {
    private String mode = "SHADOW";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
