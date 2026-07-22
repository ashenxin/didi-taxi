package com.sx.passengerapi.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;

@Component
public class PassengerSecurityStartupValidator implements InitializingBean {

    private static final Set<String> RELAXED_PROFILES = Set.of("local", "dev", "test");

    private final AppJwtProperties jwtProperties;
    private final CouponClaimIdentityProperties claimIdentityProperties;
    private final PassengerInternalClientProperties internalClientProperties;
    private final Environment environment;

    public PassengerSecurityStartupValidator(AppJwtProperties jwtProperties,
                                             CouponClaimIdentityProperties claimIdentityProperties,
                                             PassengerInternalClientProperties internalClientProperties,
                                             Environment environment) {
        this.jwtProperties = jwtProperties;
        this.claimIdentityProperties = claimIdentityProperties;
        this.internalClientProperties = internalClientProperties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!hasOnlyExplicitRelaxedProfiles(environment)) {
            validateStrict(jwtProperties, claimIdentityProperties, internalClientProperties);
        }
    }

    static void validateStrict(AppJwtProperties jwt, CouponClaimIdentityProperties claimIdentity,
                               PassengerInternalClientProperties internalClient) {
        List<String> errors = new ArrayList<>();
        validateSecret("JWT_SECRET_APP", jwt.getSecret(), "dev-didi-", errors);
        if (!"app-bff".equals(jwt.getAudience())) {
            errors.add("app.jwt.audience must be app-bff");
        }
        validateSecret("COUPON_CLAIM_IDENTITY_PHONE_HASH_SECRET",
                claimIdentity.getPhoneHashSecret(), "dev-coupon-", errors);
        validateSecret("PASSENGER_INTERNAL_TOKEN", internalClient.getToken(), "dev-passenger-", errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Production security validation failed: " + String.join("; ", errors));
        }
    }

    private static void validateSecret(String name, String value, String developmentPrefix, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(name + " must be configured");
            return;
        }
        if (!value.equals(value.strip())) {
            errors.add(name + " must not contain leading or trailing whitespace");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
            errors.add(name + " must contain at least 32 bytes");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(developmentPrefix) || normalized.contains("change-me")) {
            errors.add(name + " must not use a development default");
        }
    }

    private static boolean hasOnlyExplicitRelaxedProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length > 0 && Arrays.stream(active).allMatch(RELAXED_PROFILES::contains);
    }
}
