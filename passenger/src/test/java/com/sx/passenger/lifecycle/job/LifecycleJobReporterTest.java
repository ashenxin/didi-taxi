package com.sx.passenger.lifecycle.job;

import com.sx.passenger.lifecycle.metrics.LifecycleOrchestrationMetrics;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LifecycleJobReporterTest {
    @Test
    void marksTechnicalBatchFailureInXxlAndMetrics() {
        LifecycleOrchestrationMetrics metrics =
                mock(LifecycleOrchestrationMetrics.class);
        LifecycleXxlJobStatus xxl = mock(LifecycleXxlJobStatus.class);
        LifecycleJobReporter reporter = new LifecycleJobReporter(metrics, xxl);
        LifecycleJobBatchResult result =
                new LifecycleJobBatchResult(1, 1, 0, 1, 0, 0, 5);

        reporter.execute("recovery", () -> result);

        verify(metrics).recordJobResult("recovery", result);
        verify(xxl).fail("job=recovery, " + result.summary());
    }

    @Test
    void unexpectedFailureIsReportedInsteadOfEscapingWithoutStatus() {
        LifecycleOrchestrationMetrics metrics =
                mock(LifecycleOrchestrationMetrics.class);
        LifecycleXxlJobStatus xxl = mock(LifecycleXxlJobStatus.class);
        LifecycleJobReporter reporter = new LifecycleJobReporter(metrics, xxl);

        reporter.execute("outbox", () -> {
            throw new IllegalStateException("database unavailable");
        });

        verify(metrics).recordJobResult(
                org.mockito.ArgumentMatchers.eq("outbox"),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result.failed() == 1 && result.hasTechnicalFailure()));
        verify(xxl).fail(org.mockito.ArgumentMatchers.contains(
                "unexpectedFailure=IllegalStateException"));
    }
}
