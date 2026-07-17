package com.sx.adminapi.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class AdminSecurityStartupValidator implements InitializingBean {

    private static final Profiles RELAXED_PROFILES = Profiles.of("local", "dev", "test");

    private final AdminJwtProperties properties;
    private final Environment environment;

    public AdminSecurityStartupValidator(AdminJwtProperties properties, Environment environment) {
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
        validateJwt("JWT_SECRET_ADMIN", secret, audience, "admin-bff");
    }

    private static void validateJwt(String name, String secret, String audience, String expectedAudience) {
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || secret.toLowerCase(Locale.ROOT).startsWith("dev-didi-")
                || secret.toLowerCase(Locale.ROOT).contains("change-me")) {
            throw new IllegalStateException("Production security validation failed: " + name
                    + " must be configured with a non-development value of at least 32 bytes");
        }
        if (!expectedAudience.equals(audience)) {
            throw new IllegalStateException("Production security validation failed: admin.jwt.audience must be "
                    + expectedAudience);
        }
    }
}
