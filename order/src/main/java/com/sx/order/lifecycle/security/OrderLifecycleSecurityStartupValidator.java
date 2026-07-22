package com.sx.order.lifecycle.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class OrderLifecycleSecurityStartupValidator implements InitializingBean {
    private static final Set<String> RELAXED_PROFILES = Set.of("local", "dev", "test");

    private final OrderLifecycleInternalAuthProperties properties;
    private final Environment environment;

    public OrderLifecycleSecurityStartupValidator(OrderLifecycleInternalAuthProperties properties,
                                                  Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (hasOnlyExplicitRelaxedProfiles()) return;
        validateStrict(properties);
    }

    static void validateStrict(OrderLifecycleInternalAuthProperties properties) {
        String token = properties.getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("ORDER_LIFECYCLE_INTERNAL_TOKEN must be configured");
        }
        if (!token.equals(token.strip())) {
            throw new IllegalStateException("ORDER_LIFECYCLE_INTERNAL_TOKEN must not contain whitespace at boundaries");
        }
        if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ORDER_LIFECYCLE_INTERNAL_TOKEN must contain at least 32 bytes");
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("dev-order-") || normalized.contains("change-me")) {
            throw new IllegalStateException("ORDER_LIFECYCLE_INTERNAL_TOKEN must not use a development default");
        }
    }

    private boolean hasOnlyExplicitRelaxedProfiles() {
        String[] active = environment.getActiveProfiles();
        return active.length > 0 && Arrays.stream(active).allMatch(RELAXED_PROFILES::contains);
    }
}
