package com.sx.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewaySecurityStartupValidatorTest {

    @Test
    void rejectsMissingOrDevelopmentProductionConfiguration() {
        GatewayJwtProperties properties = new GatewayJwtProperties();
        properties.setRequireAuth(false);
        properties.setAudienceCheckEnabled(false);
        properties.setSecretAdmin("dev-didi-admin-jwt-secret-change-me-32b!!");
        properties.setSecretApp("dev-didi-app-jwt-secret-change-me-32b!!");
        properties.setSecretDriver("dev-didi-driver-jwt-secret-change-me-32b!!");

        assertThrows(IllegalStateException.class,
                () -> GatewaySecurityStartupValidator.validateStrict(properties));
    }

    @Test
    void acceptsStrongDistinctProductionConfiguration() {
        GatewayJwtProperties properties = new GatewayJwtProperties();
        properties.setRequireAuth(true);
        properties.setAudienceCheckEnabled(true);
        properties.setSecretAdmin("admin-7ZQx9vPk2mRt6Nc4Hs8Wd3Lf5Bj1Gy0K");
        properties.setSecretApp("app-4Km8Qp2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG");
        properties.setSecretDriver("driver-9Rt3Wq7Km2Pv8Ls4Hc6Nz1Fy5Bj0XdAG");

        assertDoesNotThrow(() -> GatewaySecurityStartupValidator.validateStrict(properties));
    }

    @Test
    void localProfileSkipsProductionValidation() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        GatewayJwtProperties properties = new GatewayJwtProperties();

        assertDoesNotThrow(() -> new GatewaySecurityStartupValidator(properties, environment)
                .afterPropertiesSet());
    }
}
