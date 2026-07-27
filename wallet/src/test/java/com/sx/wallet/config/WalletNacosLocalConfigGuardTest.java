package com.sx.wallet.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletNacosLocalConfigGuardTest {

    @Test
    void localProfileFailsWhenRemoteConfigMarkerIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThrows(
                IllegalStateException.class,
                () -> WalletNacosLocalConfigGuard.verify(environment)
        );
    }

    @Test
    void localProfileStartsWhenRemoteConfigMarkerIsTrue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("wallet.nacos.config-loaded", "true");
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> WalletNacosLocalConfigGuard.verify(environment));
    }

    @Test
    void nonLocalProfilesDoNotRequireRemoteConfigMarker() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertDoesNotThrow(() -> WalletNacosLocalConfigGuard.verify(environment));
    }
}
