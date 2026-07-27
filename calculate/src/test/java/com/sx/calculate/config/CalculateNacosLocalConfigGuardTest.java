package com.sx.calculate.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculateNacosLocalConfigGuardTest {

    @Test
    void localProfileFailsWhenRemoteConfigMarkerIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThrows(
                IllegalStateException.class,
                () -> CalculateNacosLocalConfigGuard.verify(environment)
        );
    }

    @Test
    void localProfileStartsWhenRemoteConfigMarkerIsTrue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("calculate.nacos.config-loaded", "true");
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> CalculateNacosLocalConfigGuard.verify(environment));
    }

    @Test
    void nonLocalProfilesDoNotRequireRemoteConfigMarker() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertDoesNotThrow(() -> CalculateNacosLocalConfigGuard.verify(environment));
    }
}
