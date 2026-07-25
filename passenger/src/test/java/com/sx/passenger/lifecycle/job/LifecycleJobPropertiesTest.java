package com.sx.passenger.lifecycle.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LifecycleJobPropertiesTest {
    @Test
    void defaultsAreBoundedAndValid() {
        LifecycleJobProperties properties = new LifecycleJobProperties();

        properties.validate();

        assertThat(properties.getOutbox().getBatchSize()).isEqualTo(50);
        assertThat(properties.getOutbox().getBatchDeadlineSeconds()).isEqualTo(25);
        assertThat(properties.getRecovery().getBatchDeadlineSeconds()).isEqualTo(55);
    }

    @Test
    void rejectsNonPositiveDeadline() {
        LifecycleJobProperties properties = new LifecycleJobProperties();
        properties.getOutbox().setBatchDeadlineSeconds(0);

        assertThatIllegalStateException().isThrownBy(properties::validate)
                .withMessageContaining("outbox.batch-deadline-seconds");
    }
}
