package com.sx.gateway.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class GatewaySecurityStartupValidator implements InitializingBean {

    private static final Profiles RELAXED_PROFILES = Profiles.of("local", "dev", "test");

    private final GatewayJwtProperties properties;
    private final PassengerWsPrecheckProperties wsPrecheckProperties;
    private final Environment environment;

    public GatewaySecurityStartupValidator(GatewayJwtProperties properties,
                                           PassengerWsPrecheckProperties wsPrecheckProperties,
                                           Environment environment) {
        this.properties = properties;
        this.wsPrecheckProperties = wsPrecheckProperties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.acceptsProfiles(RELAXED_PROFILES)) {
            return;
        }
        validateStrict(properties, wsPrecheckProperties);
    }

    static void validateStrict(GatewayJwtProperties properties,
                               PassengerWsPrecheckProperties wsPrecheckProperties) {
        List<String> errors = new ArrayList<>();
        if (!properties.isRequireAuth()) {
            errors.add("GATEWAY_JWT_REQUIRE_AUTH must be true");
        }
        if (!properties.isAudienceCheckEnabled()) {
            errors.add("gateway.jwt.audience-check-enabled must be true");
        }
        if (properties.getSecret() != null && !properties.getSecret().isBlank()) {
            errors.add("legacy JWT_SECRET/gateway.jwt.secret must be empty; configure three endpoint-specific secrets");
        }

        validateSecret("JWT_SECRET_ADMIN", properties.getSecretAdmin(), errors);
        validateSecret("JWT_SECRET_APP", properties.getSecretApp(), errors);
        validateSecret("JWT_SECRET_DRIVER", properties.getSecretDriver(), errors);

        Set<String> endpointSecrets = new HashSet<>();
        addIfPresent(endpointSecrets, properties.getSecretAdmin());
        addIfPresent(endpointSecrets, properties.getSecretApp());
        addIfPresent(endpointSecrets, properties.getSecretDriver());
        if (endpointSecrets.size() < 3) {
            errors.add("JWT_SECRET_ADMIN, JWT_SECRET_APP and JWT_SECRET_DRIVER must be different");
        }

        validateAudience("gateway.jwt.audience-admin", properties.getAudienceAdmin(), errors);
        validateAudience("gateway.jwt.audience-app", properties.getAudienceApp(), errors);
        validateAudience("gateway.jwt.audience-driver", properties.getAudienceDriver(), errors);
        if (!wsPrecheckProperties.isEnabled()) {
            errors.add("gateway.passenger-ws-precheck.enabled must be true");
        }
        validateSecret("PASSENGER_INTERNAL_TOKEN", wsPrecheckProperties.getInternalToken(), errors);
        if (wsPrecheckProperties.getServiceBaseUrl() == null
                || !wsPrecheckProperties.getServiceBaseUrl().startsWith("http://")) {
            errors.add("gateway.passenger-ws-precheck.service-base-url must use http:// service discovery URL");
        }
        if (wsPrecheckProperties.getTimeoutMillis() < 100) {
            errors.add("gateway.passenger-ws-precheck.timeout-millis must be at least 100");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Production security validation failed: " + String.join("; ", errors));
        }
    }

    private static void validateSecret(String name, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(name + " must be configured");
            return;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
            errors.add(name + " must contain at least 32 bytes");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("dev-didi-") || normalized.contains("change-me")) {
            errors.add(name + " must not use a development default");
        }
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private static void validateAudience(String name, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(name + " must not be blank");
        }
    }
}
