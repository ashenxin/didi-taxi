package com.sx.passenger.lifecycle.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleRetryPolicyTest {
    @Test
    void usesBoundedExponentialBackoff() {
        assertThat(LifecycleRetryPolicy.delay(5, 1).toSeconds()).isEqualTo(5);
        assertThat(LifecycleRetryPolicy.delay(5, 2).toSeconds()).isEqualTo(10);
        assertThat(LifecycleRetryPolicy.delay(5, 4).toSeconds()).isEqualTo(40);
        assertThat(LifecycleRetryPolicy.delay(60, 30).toSeconds()).isEqualTo(3600);
    }
}
