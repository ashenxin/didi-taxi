package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.plan.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleRuntimeSnapshotFactoryTest {

    @Test
    void createsImmutableCancellationRuntimeSnapshotWithoutSensitivePayload() {
        LifecyclePlanRegistry registry = ImmutableLifecyclePlanRegistry.from(
                new LifecyclePlanLoader().load(new PathMatchingResourcePatternResolver(),
                        "classpath*:account-lifecycle/*.yml"),
                new LifecyclePlanValidator(), new LifecyclePlanDigest());
        LifecycleIdentifierGenerator identifiers = new LifecycleIdentifierGenerator() {
            private int eventSequence;
            @Override public String nextOperationNo() { return "ALO-fixed"; }
            @Override public String nextEventId() { return "EVT-" + (++eventSequence); }
        };
        LifecycleRuntimeSnapshotFactory factory = new LifecycleRuntimeSnapshotFactory(
                registry, identifiers, new LifecycleJson());
        var command = new CreateLifecycleSnapshotCommand(
                10001L, LifecycleOperationType.ACCOUNT_CANCEL, "idem-1", "a".repeat(64), 7L,
                LifecycleActorType.CUSTOMER, "10001", "trace-1", "{\"device\":\"ios\"}",
                Instant.parse("2026-07-21T01:30:00Z"));

        LifecycleRuntimeSnapshot snapshot = factory.create(command);

        assertThat(snapshot.operation().getOperationNo()).isEqualTo("ALO-fixed");
        assertThat(snapshot.operation().getPlanCode()).isEqualTo("account-cancel");
        assertThat(snapshot.operation().getPlanVersion()).isEqualTo(1);
        assertThat(snapshot.operation().getPlanDigest()).matches("[0-9a-f]{64}");
        assertThat(snapshot.steps()).hasSize(12);
        assertThat(snapshot.steps()).extracting(step -> step.getStepCode())
                .startsWith("ORDER_FINAL_CHECK", "WALLET_FINAL_CHECK", "CALCULATE_FINAL_CHECK")
                .endsWith("ACCOUNT_FINALIZE_CANCEL");
        assertThat(snapshot.steps().getFirst().getMaxRetryCount()).isEqualTo(3);
        assertThat(snapshot.steps().getFirst().getStepConfig()).contains("timeoutSeconds");
        assertThat(snapshot.requestedEvent().getEventId()).isEqualTo("EVT-1");
        assertThat(snapshot.requestedOutbox().getEventId()).isEqualTo("EVT-2");
        assertThat(snapshot.requestedOutbox().getCausationEventId()).isEqualTo("EVT-1");
        assertThat(snapshot.requestedOutbox().getPayload().toLowerCase())
                .doesNotContain("phone", "otp", "token", "password", "device");
        assertThatThrownBy(() -> snapshot.steps().add(snapshot.steps().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidRequestHashBeforeCreatingSnapshot() {
        LifecyclePlanRegistry registry = ImmutableLifecyclePlanRegistry.from(
                new LifecyclePlanLoader().load(new PathMatchingResourcePatternResolver(),
                        "classpath*:account-lifecycle/*.yml"),
                new LifecyclePlanValidator(), new LifecyclePlanDigest());
        LifecycleRuntimeSnapshotFactory factory = new LifecycleRuntimeSnapshotFactory(
                registry, new UuidLifecycleIdentifierGenerator(), new LifecycleJson());

        assertThatThrownBy(() -> factory.create(new CreateLifecycleSnapshotCommand(
                1, LifecycleOperationType.PHONE_CHANGE, "idem", "not-a-sha", 0,
                LifecycleActorType.CUSTOMER, "1", null, null, Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHash");
    }
}
