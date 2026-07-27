package com.sx.driverapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DriverApiNacosLocalConfigGuardTest {

    @Test
    void localProfileFailsWhenRemoteConfigMarkerIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThrows(
                IllegalStateException.class,
                () -> DriverApiNacosLocalConfigGuard.verify(environment)
        );
    }

    @Test
    void localProfileStartsWhenRemoteConfigMarkerIsTrue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("driver.nacos.config-loaded", "true");
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> DriverApiNacosLocalConfigGuard.verify(environment));
    }

    @Test
    void nonLocalProfilesDoNotRequireRemoteConfigMarker() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertDoesNotThrow(() -> DriverApiNacosLocalConfigGuard.verify(environment));
    }
}
