package com.sx.passenger.internal.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class PassengerInternalSecurityStartupValidator implements InitializingBean {

    private static final Profiles RELAXED_PROFILES = Profiles.of("local", "dev", "test");

    private final PassengerInternalAuthProperties properties;
    private final Environment environment;

    public PassengerInternalSecurityStartupValidator(PassengerInternalAuthProperties properties,
                                                      Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.acceptsProfiles(RELAXED_PROFILES)) {
            return;
        }
        validateStrict(properties);
    }

    static void validateStrict(PassengerInternalAuthProperties properties) {
        String token = properties.getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("PASSENGER_INTERNAL_TOKEN must be configured");
        }
        if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("PASSENGER_INTERNAL_TOKEN must contain at least 32 bytes");
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("dev-passenger-") || normalized.contains("change-me")) {
            throw new IllegalStateException("PASSENGER_INTERNAL_TOKEN must not use a development default");
        }
    }
}
