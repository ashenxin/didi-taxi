package com.sx.order.lifecycle.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderLifecycleSecurityStartupValidatorTest {

    @Test
    void strictProfilesRejectMissingShortWhitespaceAndDevelopmentTokens() {
        for (String token : new String[]{null, "short", " dev-order-lifecycle-secret-over-32-bytes",
                "dev-order-lifecycle-secret-over-32-bytes", "order-lifecycle-change-me-secret-over-32-bytes"}) {
            assertThrows(IllegalStateException.class, () -> validator(token, new MockEnvironment()).afterPropertiesSet());
        }
    }

    @Test
    void strictProfileAcceptsStrongIndependentToken() {
        assertDoesNotThrow(() -> validator(
                "order-4Km8Qp2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG", new MockEnvironment()).afterPropertiesSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "test"})
    void explicitlyRelaxedProfileAllowsDevelopmentDefault(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        assertDoesNotThrow(() -> validator("dev-order-lifecycle-change-me", environment).afterPropertiesSet());
    }

    @Test
    void missingOrMixedRelaxedProfilesStillValidateStrictly() {
        for (String[] profiles : new String[][]{{}, {"prod", "dev"}, {"test", "prod"}}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            assertThrows(IllegalStateException.class,
                    () -> validator("dev-order-lifecycle-change-me", environment).afterPropertiesSet());
        }
    }

    private static OrderLifecycleSecurityStartupValidator validator(
            String token, MockEnvironment environment) {
        OrderLifecycleInternalAuthProperties properties = new OrderLifecycleInternalAuthProperties();
        properties.setToken(token);
        return new OrderLifecycleSecurityStartupValidator(properties, environment);
    }
}
