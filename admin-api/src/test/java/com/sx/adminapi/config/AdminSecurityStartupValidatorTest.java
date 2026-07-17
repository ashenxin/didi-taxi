package com.sx.adminapi.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminSecurityStartupValidatorTest {

    @Test
    void rejectsDevelopmentSecret() {
        assertThrows(IllegalStateException.class, () -> AdminSecurityStartupValidator.validateStrict(
                "dev-didi-admin-jwt-secret-change-me-32b!!", "admin-bff"));
    }

    @Test
    void acceptsStrongSecretAndExpectedAudience() {
        assertDoesNotThrow(() -> AdminSecurityStartupValidator.validateStrict(
                "admin-7ZQx9vPk2mRt6Nc4Hs8Wd3Lf5Bj1Gy0K", "admin-bff"));
    }
}
