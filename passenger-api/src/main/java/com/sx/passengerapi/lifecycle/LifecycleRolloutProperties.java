package com.sx.passengerapi.lifecycle;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 旧 settings 入口转调统一生命周期流程的灰度配置。 */
@Component
@ConfigurationProperties(prefix = "passenger.account-lifecycle.rollout")
public class LifecycleRolloutProperties {
    private boolean enabled;
    private int percent;

    @PostConstruct
    void validate() {
        if (percent < 0 || percent > 100) {
            throw new IllegalStateException(
                    "passenger.account-lifecycle.rollout.percent必须在0到100之间");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPercent() {
        return percent;
    }

    public void setPercent(int percent) {
        this.percent = percent;
    }
}
