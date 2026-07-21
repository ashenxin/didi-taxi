package com.sx.passenger.lifecycle.plan;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

@Configuration
public class LifecyclePlanConfiguration {
    @Bean
    LifecyclePlanRegistry lifecyclePlanRegistry(ResourcePatternResolver resources) {
        LifecyclePlanLoader loader = new LifecyclePlanLoader();
        return ImmutableLifecyclePlanRegistry.from(
                loader.load(resources, "classpath*:account-lifecycle/*.yml"),
                new LifecyclePlanValidator(), new LifecyclePlanDigest());
    }
}
