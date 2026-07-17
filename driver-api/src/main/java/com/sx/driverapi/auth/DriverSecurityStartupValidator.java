package com.sx.driverapi.auth;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class DriverSecurityStartupValidator implements InitializingBean {

    private static final Profiles RELAXED_PROFILES = Profiles.of("local", "dev", "test");

    private final DriverJwtProperties properties;
    private final Environment environment;

    public DriverSecurityStartupValidator(DriverJwtProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(RELAXED_PROFILES)) {
            validateStrict(properties.getSecret(), properties.getAudience());
        }
    }

    static void validateStrict(String secret, String audience) {
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || secret.toLowerCase(Locale.ROOT).startsWith("dev-didi-")
                || secret.toLowerCase(Locale.ROOT).contains("change-me")) {
            throw new IllegalStateException("Production security validation failed: JWT_SECRET_DRIVER"
                    + " must be configured with a non-development value of at least 32 bytes");
        }
        if (!"driver-bff".equals(audience)) {
            throw new IllegalStateException("Production security validation failed: app.jwt.audience must be driver-bff");
        }
    }
}
