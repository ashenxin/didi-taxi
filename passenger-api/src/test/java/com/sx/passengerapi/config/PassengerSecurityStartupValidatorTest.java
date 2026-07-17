package com.sx.passengerapi.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassengerSecurityStartupValidatorTest {

    @Test
    void rejectsDevelopmentDefaults() {
        AppJwtProperties jwt = new AppJwtProperties();
        jwt.setSecret("dev-didi-app-jwt-secret-change-me-32b!!");
        CouponClaimIdentityProperties claim = new CouponClaimIdentityProperties();
        claim.setPhoneHashSecret("dev-coupon-claim-secret-change-me");

        assertThrows(IllegalStateException.class,
                () -> PassengerSecurityStartupValidator.validateStrict(jwt, claim));
    }

    @Test
    void acceptsStrongSecretsAndExpectedAudience() {
        AppJwtProperties jwt = new AppJwtProperties();
        jwt.setSecret("app-4Km8Qp2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG");
        CouponClaimIdentityProperties claim = new CouponClaimIdentityProperties();
        claim.setPhoneHashSecret("coupon-2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG4Km8Qp");

        assertDoesNotThrow(() -> PassengerSecurityStartupValidator.validateStrict(jwt, claim));
    }
}
