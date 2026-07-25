package com.sx.passenger.lifecycle.metrics;

import com.sx.passenger.lifecycle.job.LifecycleJobBatchResult;
import com.sx.passenger.lifecycle.job.LifecycleJobProperties;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LifecycleOrchestrationMetricsTest {
    @Test
    void refreshesBacklogDetailAndRecordsJobCounters() {
        LifecycleOperationMapper operations = mock(LifecycleOperationMapper.class);
        LifecycleStepMapper steps = mock(LifecycleStepMapper.class);
        LifecycleOutboxMapper outbox = mock(LifecycleOutboxMapper.class);
        when(operations.selectCount(any())).thenReturn(2L, 1L);
        when(steps.selectCount(any())).thenReturn(3L);
        when(outbox.selectCount(any())).thenReturn(4L, 1L, 1L);
        when(outbox.selectOne(any())).thenReturn(new LifecycleOutboxEntity()
                .setCreatedAt(LocalDateTime.now().minusSeconds(120)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LifecycleOrchestrationMetrics metrics = new LifecycleOrchestrationMetrics(
                operations, steps, outbox, new LifecycleJobProperties(), registry);

        metrics.refresh();
        metrics.recordJobResult("outbox",
                new LifecycleJobBatchResult(3, 3, 1, 1, 1, 0, 10));

        assertThat(registry.get("passenger.lifecycle.operation.due")
                .gauge().value()).isEqualTo(2);
        assertThat(registry.get("passenger.lifecycle.step.timed_out")
                .gauge().value()).isEqualTo(3);
        assertThat(registry.get("passenger.lifecycle.outbox.backlog")
                .gauge().value()).isEqualTo(4);
        assertThat(registry.get("passenger.lifecycle.outbox.exhausted")
                .gauge().value()).isEqualTo(1);
        assertThat(registry.get("passenger.lifecycle.outbox.stale_processing")
                .gauge().value()).isEqualTo(1);
        assertThat(registry.get("passenger.lifecycle.outbox.oldest.age.seconds")
                .gauge().value()).isGreaterThanOrEqualTo(119);
        assertThat(registry.get("passenger.lifecycle.job.items")
                .tags("job", "outbox", "result", "failed")
                .counter().count()).isEqualTo(1);
    }
}
