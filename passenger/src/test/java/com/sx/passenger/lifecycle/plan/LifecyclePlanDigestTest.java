package com.sx.passenger.lifecycle.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecyclePlanDigestTest {

    private final LifecyclePlanDigest digest = new LifecyclePlanDigest();

    @Test
    void digestIsDeterministicAndChangesWithExecutionFields() {
        LifecyclePlanDefinition first = plan(10);
        LifecyclePlanDefinition equivalent = plan(10);
        LifecyclePlanDefinition changed = plan(11);

        assertThat(digest.sha256(first)).matches("[0-9a-f]{64}");
        assertThat(digest.sha256(equivalent)).isEqualTo(digest.sha256(first));
        assertThat(digest.sha256(changed)).isNotEqualTo(digest.sha256(first));
    }

    private static LifecyclePlanDefinition plan(int timeout) {
        var step = new LifecycleStepDefinition("IDENTITY_COMMIT_PHONE_CHANGE", "IDENTITY", "ACTION",
                "LOCAL_TRANSACTION", "REQUIRED", 100, timeout, new LifecycleRetryDefinition(0, 5));
        return new LifecyclePlanDefinition(1, "phone-change", 1, "PHONE_CHANGE", "ACTIVE", "test", List.of(step));
    }
}
