package com.sx.capacity.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NacosLocalConfigGuardTest {

    @Test
    void localProfileFailsWhenRemoteConfigMarkerIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThrows(
                IllegalStateException.class,
                () -> NacosLocalConfigGuard.verify(environment)
        );
    }

    @Test
    void localProfileStartsWhenRemoteConfigMarkerIsTrue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("capacity.nacos.config-loaded", "true");
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> NacosLocalConfigGuard.verify(environment));
    }

    @Test
    void nonLocalProfilesDoNotRequireRemoteConfigMarker() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertDoesNotThrow(() -> NacosLocalConfigGuard.verify(environment));
    }
}
