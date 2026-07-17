package com.sx.wallet.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentStartupValidatorTest {

    @Test
    void enabledMockIsAllowedOnlyInConfiguredLocalProfiles() {
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(true);
        properties.setAllowedProfiles(List.of("local", "dev", "test"));
        MockPaymentStartupValidator validator = new MockPaymentStartupValidator(properties, null);

        assertThatCode(() -> validator.validate(List.of("local"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(List.of("test"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(List.of("prod")))
                .hasMessageContaining("禁止启用mock支付");
        assertThatThrownBy(() -> validator.validate(List.of("staging")))
                .hasMessageContaining("禁止启用mock支付");
        assertThatThrownBy(() -> validator.validate(List.of("local", "prod")))
                .hasMessageContaining("禁止启用mock支付");
        assertThatThrownBy(() -> validator.validate(List.of()))
                .hasMessageContaining("禁止启用mock支付");
    }

    @Test
    void configuredProfilesCanOnlyNarrowBuiltInSafeProfiles() {
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(true);
        properties.setAllowedProfiles(List.of("prod"));
        MockPaymentStartupValidator validator = new MockPaymentStartupValidator(properties, null);

        assertThatThrownBy(() -> validator.validate(List.of("prod")))
                .hasMessageContaining("禁止启用mock支付");
        assertThatThrownBy(() -> validator.validate(List.of("local")))
                .hasMessageContaining("禁止启用mock支付");
    }

    @Test
    void disabledMockDoesNotRestrictDeploymentProfile() {
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(false);
        MockPaymentStartupValidator validator = new MockPaymentStartupValidator(properties, null);

        assertThatCode(() -> validator.validate(List.of("prod"))).doesNotThrowAnyException();
    }
}
