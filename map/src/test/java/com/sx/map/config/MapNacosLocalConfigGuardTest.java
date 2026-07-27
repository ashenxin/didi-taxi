package com.sx.map.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapNacosLocalConfigGuardTest {

    @Test
    void localProfileFailsWhenRemoteConfigMarkerIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThrows(
                IllegalStateException.class,
                () -> MapNacosLocalConfigGuard.verify(environment)
        );
    }

    @Test
    void localProfileStartsWhenRemoteConfigMarkerIsTrue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("map.nacos.config-loaded", "true");
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> MapNacosLocalConfigGuard.verify(environment));
    }

    @Test
    void nonLocalProfilesDoNotRequireRemoteConfigMarker() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertDoesNotThrow(() -> MapNacosLocalConfigGuard.verify(environment));
    }
}
