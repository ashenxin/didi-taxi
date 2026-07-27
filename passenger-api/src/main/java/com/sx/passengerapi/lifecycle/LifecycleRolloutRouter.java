package com.sx.passengerapi.lifecycle;

import org.springframework.stereotype.Component;

/** 按 customerId 稳定分桶，保证同一乘客在同一比例下始终命中相同路径。 */
@Component
public class LifecycleRolloutRouter {
    private final LifecycleRolloutProperties properties;

    public LifecycleRolloutRouter(LifecycleRolloutProperties properties) {
        this.properties = properties;
    }

    public boolean useLifecycle(long customerId) {
        if (!properties.isEnabled() || properties.getPercent() == 0) {
            return false;
        }
        if (properties.getPercent() == 100) {
            return true;
        }
        int bucket = Math.floorMod(Long.hashCode(customerId), 100);
        return bucket < properties.getPercent();
    }
}
