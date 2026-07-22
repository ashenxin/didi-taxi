package com.sx.order.lifecycle.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class OrderLifecycleMetricsTest {
    @Test
    void registryFailuresNeverChangeBusinessDecision() {
        OrderLifecycleMetrics metrics = new OrderLifecycleMetrics(mock(MeterRegistry.class));

        assertThatCode(() -> metrics.writeFence("RIDE_CREATE",
                OrderLifecycleMetrics.WriteFenceDecision.BLOCKED)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.projectionApply(
                OrderLifecycleMetrics.ProjectionResult.UNKNOWN)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.participantCommand("ORDER_FINAL_CHECK",
                OrderLifecycleMetrics.ParticipantDecision.PASS)).doesNotThrowAnyException();
    }

    @Test
    void recordsOnlyWhitelistedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderLifecycleMetrics metrics = new OrderLifecycleMetrics(registry);
        metrics.writeFence("RIDE_CREATE", OrderLifecycleMetrics.WriteFenceDecision.ALLOW);
        metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.APPLIED);
        metrics.participantCommand("ORDER_FINAL_CHECK",
                OrderLifecycleMetrics.ParticipantDecision.BLOCKED);

        assertThat(registry.get("order.lifecycle.write_fence")
                .tag("actionCode", "ride_create").tag("decision", "allow").counter().count()).isEqualTo(1);
        assertThat(registry.get("order.lifecycle.projection.apply")
                .tag("result", "applied").counter().count()).isEqualTo(1);
        assertThat(registry.get("order.lifecycle.participant.command")
                .tag("stepCode", "order_final_check").tag("decision", "blocked")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void successfulOutcomesWaitForCommitAndRollbackBecomesUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderLifecycleMetrics metrics = new OrderLifecycleMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.APPLIED);
            metrics.participantCommand("ORDER_FINAL_CHECK",
                    OrderLifecycleMetrics.ParticipantDecision.PASS);
            metrics.writeFence("RIDE_CREATE", OrderLifecycleMetrics.WriteFenceDecision.ALLOW);

            assertThat(count(registry, "order.lifecycle.projection.apply", "result", "applied")).isZero();
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(count(registry, "order.lifecycle.projection.apply", "result", "unknown"))
                    .isEqualTo(1);
            assertThat(count(registry, "order.lifecycle.participant.command", "decision", "unknown"))
                    .isEqualTo(1);
            assertThat(count(registry, "order.lifecycle.write_fence", "decision", "unknown"))
                    .isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void conflictAndUnknownOutcomesAreRecordedEvenWhenTransactionRollsBack() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderLifecycleMetrics metrics = new OrderLifecycleMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.CONFLICT);
            metrics.participantCommand("ORDER_FINAL_CHECK",
                    OrderLifecycleMetrics.ParticipantDecision.CONFLICT);

            assertThat(count(registry, "order.lifecycle.projection.apply", "result", "conflict"))
                    .isEqualTo(1);
            assertThat(count(registry, "order.lifecycle.participant.command", "decision", "conflict"))
                    .isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static double count(SimpleMeterRegistry registry, String name, String key, String value) {
        var counter = registry.find(name).tag(key, value).counter();
        return counter == null ? 0 : counter.count();
    }
}
