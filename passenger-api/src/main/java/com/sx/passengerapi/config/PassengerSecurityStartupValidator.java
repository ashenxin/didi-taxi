package com.sx.passengerapi.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class PassengerSecurityStartupValidator implements InitializingBean {

    private static final Profiles RELAXED_PROFILES = Profiles.of("local", "dev", "test");

    private final AppJwtProperties jwtProperties;
    private final CouponClaimIdentityProperties claimIdentityProperties;
    private final Environment environment;

    public PassengerSecurityStartupValidator(AppJwtProperties jwtProperties,
                                             CouponClaimIdentityProperties claimIdentityProperties,
                                             Environment environment) {
        this.jwtProperties = jwtProperties;
        this.claimIdentityProperties = claimIdentityProperties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(RELAXED_PROFILES)) {
            validateStrict(jwtProperties, claimIdentityProperties);
        }
    }

    static void validateStrict(AppJwtProperties jwt, CouponClaimIdentityProperties claimIdentity) {
        List<String> errors = new ArrayList<>();
        validateSecret("JWT_SECRET_APP", jwt.getSecret(), "dev-didi-", errors);
        if (!"app-bff".equals(jwt.getAudience())) {
            errors.add("app.jwt.audience must be app-bff");
        }
        validateSecret("COUPON_CLAIM_IDENTITY_PHONE_HASH_SECRET",
                claimIdentity.getPhoneHashSecret(), "dev-coupon-", errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Production security validation failed: " + String.join("; ", errors));
        }
    }

    private static void validateSecret(String name, String value, String developmentPrefix, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(name + " must be configured");
            return;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
            errors.add(name + " must contain at least 32 bytes");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(developmentPrefix) || normalized.contains("change-me")) {
            errors.add(name + " must not use a development default");
        }
    }
}
