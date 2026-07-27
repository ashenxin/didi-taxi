package com.sx.passengerapi.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleRolloutRouterTest {

    @Test
    void disabledAndZeroPercentNeverRouteToLifecycle() {
        LifecycleRolloutProperties properties = new LifecycleRolloutProperties();
        properties.setEnabled(false);
        properties.setPercent(100);
        LifecycleRolloutRouter router = new LifecycleRolloutRouter(properties);
        assertThat(router.useLifecycle(7L)).isFalse();

        properties.setEnabled(true);
        properties.setPercent(0);
        assertThat(router.useLifecycle(7L)).isFalse();
    }

    @Test
    void fullRolloutAlwaysRoutesAndPartialRolloutIsStable() {
        LifecycleRolloutProperties properties = new LifecycleRolloutProperties();
        properties.setEnabled(true);
        properties.setPercent(100);
        LifecycleRolloutRouter router = new LifecycleRolloutRouter(properties);
        assertThat(router.useLifecycle(7L)).isTrue();

        properties.setPercent(20);
        boolean first = router.useLifecycle(123456L);
        for (int i = 0; i < 100; i++) {
            assertThat(router.useLifecycle(123456L)).isEqualTo(first);
        }
    }

    @Test
    void rejectsInvalidPercentAtStartup() {
        LifecycleRolloutProperties properties = new LifecycleRolloutProperties();
        properties.setPercent(101);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class);
    }
}
