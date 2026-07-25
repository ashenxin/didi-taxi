package com.sx.passenger.lifecycle.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LifecycleJobBatchResultTest {
    @Test
    void mergesCountsAndDetectsTechnicalFailure() {
        LifecycleJobBatchResult first =
                new LifecycleJobBatchResult(3, 2, 1, 1, 0, 1, 10);
        LifecycleJobBatchResult second =
                new LifecycleJobBatchResult(2, 2, 1, 0, 1, 0, 5);

        LifecycleJobBatchResult merged = first.merge(second);

        assertThat(merged).isEqualTo(
                new LifecycleJobBatchResult(5, 4, 2, 1, 1, 1, 15));
        assertThat(merged.hasTechnicalFailure()).isTrue();
        assertThat(merged.summary()).contains("failed=1", "exhausted=1");
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LifecycleJobBatchResult(0, 0, 0, -1, 0, 0, 0));
    }
}
