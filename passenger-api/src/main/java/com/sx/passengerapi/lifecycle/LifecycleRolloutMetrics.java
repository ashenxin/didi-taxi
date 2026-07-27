package com.sx.passengerapi.lifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 仅使用固定低基数标签记录旧入口灰度路由和结果。 */
@Component
public class LifecycleRolloutMetrics {
    private final MeterRegistry registry;

    public LifecycleRolloutMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String route, String result) {
        Counter.builder("passenger.lifecycle.entry.requests")
                .tag("operation", operation)
                .tag("route", route)
                .tag("result", result)
                .register(registry)
                .increment();
    }
}
