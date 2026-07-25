package com.sx.calculate.lifecycle.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class CalculateLifecycleMetricsTest {

    @Test
    void recordsOnlyWhitelistedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CalculateLifecycleMetrics metrics = new CalculateLifecycleMetrics(registry);

        metrics.writeFence("COUPON_LOCK", CalculateLifecycleMetrics.WriteFenceDecision.BLOCKED);
        metrics.projectionApply(CalculateLifecycleMetrics.ProjectionResult.APPLIED);
        metrics.participantCommand("CALCULATE_CLEAR_POINTS",
                CalculateLifecycleMetrics.ParticipantDecision.PASS);
        metrics.resultQuery("operation-with-sensitive-value",
                CalculateLifecycleMetrics.QueryResult.NOT_FOUND);

        assertThat(registry.get("calculate.lifecycle.write_fence")
                .tag("action", "coupon_lock").tag("decision", "blocked").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("calculate.lifecycle.participant.command")
                .tag("stepCode", "calculate_clear_points").counter().count()).isEqualTo(1);
        assertThat(registry.get("calculate.lifecycle.result.query")
                .tag("stepCode", "unknown").counter().count()).isEqualTo(1);
    }

    @Test
    void registryFailureNeverChangesBusinessOutcome() {
        CalculateLifecycleMetrics metrics = new CalculateLifecycleMetrics(mock(MeterRegistry.class));
        assertThatCode(() -> metrics.participantCommand("CALCULATE_FINAL_CHECK",
                CalculateLifecycleMetrics.ParticipantDecision.PASS)).doesNotThrowAnyException();
    }
}
