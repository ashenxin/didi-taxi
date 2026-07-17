package com.sx.order.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockTripMetricsProviderTest {

    @Test
    void defaultConfigurationUsesPlannedDuration() {
        MockTripMetricsProvider provider = new MockTripMetricsProvider("mock-trip-v1", 0);

        assertThat(provider.generateDurationSeconds("T202607170001", 1_560L)).isEqualTo(1_560L);
        assertThat(provider.version()).isEqualTo("mock-trip-v1");
    }

    @Test
    void configuredVariationIsStableForSameOrder() {
        MockTripMetricsProvider provider = new MockTripMetricsProvider("mock-trip-v2", 20);

        long first = provider.generateDurationSeconds("T202607170001", 1_560L);
        long second = provider.generateDurationSeconds("T202607170001", 1_560L);

        assertThat(second).isEqualTo(first);
        assertThat(first).isBetween(1_248L, 1_872L);
    }
}
