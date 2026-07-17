package com.sx.driverapi.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DriverSecurityStartupValidatorTest {

    @Test
    void rejectsDevelopmentSecret() {
        assertThrows(IllegalStateException.class, () -> DriverSecurityStartupValidator.validateStrict(
                "dev-didi-driver-jwt-secret-change-me-32b!!", "driver-bff"));
    }

    @Test
    void acceptsStrongSecretAndExpectedAudience() {
        assertDoesNotThrow(() -> DriverSecurityStartupValidator.validateStrict(
                "driver-9Rt3Wq7Km2Pv8Ls4Hc6Nz1Fy5Bj0XdAG", "driver-bff"));
    }
}
