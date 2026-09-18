package com.sx.map.internal.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class MapInternalSecurityStartupValidator implements InitializingBean {

    private static final Set<String> RELAXED_PROFILES = Set.of("local", "dev", "test");

    private final MapInternalAuthProperties properties;
    private final Environment environment;

    public MapInternalSecurityStartupValidator(MapInternalAuthProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (hasOnlyExplicitRelaxedProfiles(environment)) {
            return;
        }
        validateStrict(properties);
    }

    static void validateStrict(MapInternalAuthProperties properties) {
        String token = properties.getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("MAP_INTERNAL_TOKEN must be configured");
        }
        if (!token.equals(token.strip())) {
            throw new IllegalStateException("MAP_INTERNAL_TOKEN must not contain leading or trailing whitespace");
        }
        if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("MAP_INTERNAL_TOKEN must contain at least 32 bytes");
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("dev-map-") || normalized.contains("change-me")) {
            throw new IllegalStateException("MAP_INTERNAL_TOKEN must not use a development default");
        }
    }

    private static boolean hasOnlyExplicitRelaxedProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length > 0 && Arrays.stream(active).allMatch(RELAXED_PROFILES::contains);
    }
}
