package com.sx.passenger.internal.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassengerInternalSecurityStartupValidatorTest {

    @Test
    void rejectsMissingTokenOutsideRelaxedProfiles() {
        assertStrictValidationFails(null);
    }

    @Test
    void rejectsTokenShorterThanThirtyTwoBytesOutsideRelaxedProfiles() {
        assertStrictValidationFails("too-short");
    }

    @Test
    void rejectsDevelopmentPrefixOutsideRelaxedProfiles() {
        assertStrictValidationFails("dev-passenger-internal-secret-over-32-bytes");
    }

    @Test
    void rejectsChangeMeTokenOutsideRelaxedProfiles() {
        assertStrictValidationFails("passenger-internal-change-me-secret-over-32-bytes");
    }

    @Test
    void acceptsStrongTokenOutsideRelaxedProfiles() {
        PassengerInternalAuthProperties properties = properties("passenger-4Km8Qp2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG");

        assertDoesNotThrow(() -> new PassengerInternalSecurityStartupValidator(
                properties, new MockEnvironment()).afterPropertiesSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "test"})
    void relaxedProfilesAllowDevelopmentDefault(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        assertDoesNotThrow(() -> new PassengerInternalSecurityStartupValidator(
                properties("dev-passenger-internal-change-me"), environment).afterPropertiesSet());
    }

    private static void assertStrictValidationFails(String token) {
        assertThrows(IllegalStateException.class, () -> new PassengerInternalSecurityStartupValidator(
                properties(token), new MockEnvironment()).afterPropertiesSet());
    }

    private static PassengerInternalAuthProperties properties(String token) {
        PassengerInternalAuthProperties properties = new PassengerInternalAuthProperties();
        properties.setToken(token);
        return properties;
    }
}
