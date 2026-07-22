package com.sx.passengerapi.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.mock.env.MockEnvironment;

class PassengerSecurityStartupValidatorTest {

    @Test
    void rejectsDevelopmentDefaults() {
        AppJwtProperties jwt = new AppJwtProperties();
        jwt.setSecret("dev-didi-app-jwt-secret-change-me-32b!!");
        CouponClaimIdentityProperties claim = new CouponClaimIdentityProperties();
        claim.setPhoneHashSecret("dev-coupon-claim-secret-change-me");

        assertThrows(IllegalStateException.class,
                () -> PassengerSecurityStartupValidator.validateStrict(jwt, claim, internal("strong-passenger-internal-token-32bytes")));
    }

    @Test
    void acceptsStrongSecretsAndExpectedAudience() {
        AppJwtProperties jwt = new AppJwtProperties();
        jwt.setSecret("app-4Km8Qp2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG");
        CouponClaimIdentityProperties claim = new CouponClaimIdentityProperties();
        claim.setPhoneHashSecret("coupon-2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG4Km8Qp");

        assertDoesNotThrow(() -> PassengerSecurityStartupValidator.validateStrict(
                jwt, claim, internal("prod-passenger-internal-token-32bytes")));
    }

    @Test
    void productionRejectsMissingShortAndDevelopmentInternalToken() {
        AppJwtProperties jwt = strongJwt();
        CouponClaimIdentityProperties claim = strongCouponIdentity();

        for (String token : new String[]{null, "", "too-short", "dev-passenger-internal-01234567890123456789",
                "prod-passenger-change-me-01234567890123456789"}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> PassengerSecurityStartupValidator.validateStrict(jwt, claim, internal(token)));
            assertThat(failure).hasMessageContaining("PASSENGER_INTERNAL_TOKEN");
        }
    }

    @Test
    void productionRequiresAppBffAudience() {
        AppJwtProperties jwt = strongJwt();
        jwt.setAudience("wrong-bff");

        assertThat(assertThrows(IllegalStateException.class,
                () -> PassengerSecurityStartupValidator.validateStrict(
                        jwt, strongCouponIdentity(), internal("prod-passenger-internal-token-32bytes"))))
                .hasMessageContaining("app.jwt.audience must be app-bff");
    }

    @Test
    void mixedOrMissingRelaxedProfileStillUsesStrictValidation() {
        for (String[] active : new String[][]{{"prod", "dev"}, {"test", "prod"}, {}}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(active);
            assertThrows(IllegalStateException.class, () -> new PassengerSecurityStartupValidator(
                    new AppJwtProperties(), new CouponClaimIdentityProperties(),
                    internal("dev-passenger-internal-change-me"), environment).afterPropertiesSet());
        }
    }

    @Test
    void localOnlyProfileRemainsRelaxedButStrictTokenRejectsWhitespacePadding() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertDoesNotThrow(() -> new PassengerSecurityStartupValidator(
                new AppJwtProperties(), new CouponClaimIdentityProperties(),
                internal("dev-passenger-internal-change-me"), local).afterPropertiesSet());

        for (String token : new String[]{" prod-passenger-internal-token-32bytes",
                "prod-passenger-internal-token-32bytes "}) {
            assertThat(assertThrows(IllegalStateException.class, () -> PassengerSecurityStartupValidator.validateStrict(
                    strongJwt(), strongCouponIdentity(), internal(token))))
                    .hasMessageContaining("PASSENGER_INTERNAL_TOKEN");
        }
    }

    private static AppJwtProperties strongJwt() {
        AppJwtProperties jwt = new AppJwtProperties();
        jwt.setSecret("app-4Km8Qp2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG");
        return jwt;
    }

    private static CouponClaimIdentityProperties strongCouponIdentity() {
        CouponClaimIdentityProperties claim = new CouponClaimIdentityProperties();
        claim.setPhoneHashSecret("coupon-2Xv7Ls9Rt5Nz1Hc6Wd3Fy0BjAG4Km8Qp");
        return claim;
    }

    private static PassengerInternalClientProperties internal(String token) {
        PassengerInternalClientProperties internal = new PassengerInternalClientProperties();
        internal.setToken(token);
        return internal;
    }
}
