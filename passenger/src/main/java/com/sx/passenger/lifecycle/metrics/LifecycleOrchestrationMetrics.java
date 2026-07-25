package com.sx.passenger.lifecycle.metrics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.job.LifecycleJobBatchResult;
import com.sx.passenger.lifecycle.job.LifecycleJobProperties;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LifecycleOrchestrationMetrics {
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final LifecycleOutboxMapper outbox;
    private final MeterRegistry registry;
    private final LifecycleJobProperties properties;
    private final AtomicLong dueOperations = new AtomicLong();
    private final AtomicLong timedOutSteps = new AtomicLong();
    private final AtomicLong outboxBacklog = new AtomicLong();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();
    private final AtomicLong outboxExhausted = new AtomicLong();
    private final AtomicLong outboxStaleProcessing = new AtomicLong();
    private final AtomicLong manualReview = new AtomicLong();

    public LifecycleOrchestrationMetrics(
            LifecycleOperationMapper operations,
            LifecycleStepMapper steps,
            LifecycleOutboxMapper outbox,
            LifecycleJobProperties properties,
            MeterRegistry registry) {
        this.operations = operations;
        this.steps = steps;
        this.outbox = outbox;
        this.properties = properties;
        this.registry = registry;
        registry.gauge("passenger.lifecycle.operation.due", dueOperations);
        registry.gauge("passenger.lifecycle.step.timed_out", timedOutSteps);
        registry.gauge("passenger.lifecycle.outbox.backlog", outboxBacklog);
        registry.gauge("passenger.lifecycle.outbox.oldest.age.seconds",
                outboxOldestAgeSeconds);
        registry.gauge("passenger.lifecycle.outbox.exhausted", outboxExhausted);
        registry.gauge("passenger.lifecycle.outbox.stale_processing",
                outboxStaleProcessing);
        registry.gauge("passenger.lifecycle.operation.manual_review", manualReview);
    }

    public void refresh() {
        LocalDateTime now = LocalDateTime.now();
        dueOperations.set(operations.selectCount(new LambdaQueryWrapper<LifecycleOperationEntity>()
                .in(LifecycleOperationEntity::getStatus,
                        "FENCED", "VALIDATING", "EXECUTING", "RETRY_PENDING")
                .and(query -> query.isNull(LifecycleOperationEntity::getNextWakeupAt)
                        .or().le(LifecycleOperationEntity::getNextWakeupAt, now))));
        timedOutSteps.set(steps.selectCount(new LambdaQueryWrapper<LifecycleStepEntity>()
                .eq(LifecycleStepEntity::getStatus, "RUNNING")
                .le(LifecycleStepEntity::getTimeoutAt, now)));
        outboxBacklog.set(outbox.selectCount(new LambdaQueryWrapper<LifecycleOutboxEntity>()
                .in(LifecycleOutboxEntity::getStatus, "PENDING", "FAILED", "PROCESSING")));
        LifecycleOutboxEntity oldest = outbox.selectOne(
                new LambdaQueryWrapper<LifecycleOutboxEntity>()
                        .in(LifecycleOutboxEntity::getStatus,
                                "PENDING", "FAILED", "PROCESSING")
                        .orderByAsc(LifecycleOutboxEntity::getCreatedAt)
                        .last("LIMIT 1"));
        outboxOldestAgeSeconds.set(oldest == null || oldest.getCreatedAt() == null
                ? 0
                : Math.max(0, Duration.between(oldest.getCreatedAt(), now).getSeconds()));
        outboxExhausted.set(outbox.selectCount(
                new LambdaQueryWrapper<LifecycleOutboxEntity>()
                        .in(LifecycleOutboxEntity::getStatus, "PENDING", "FAILED")
                        .apply("retry_count >= max_retry_count")));
        outboxStaleProcessing.set(outbox.selectCount(
                new LambdaQueryWrapper<LifecycleOutboxEntity>()
                        .eq(LifecycleOutboxEntity::getStatus, "PROCESSING")
                        .le(LifecycleOutboxEntity::getProcessingAt,
                                now.minusSeconds(properties.getOutbox()
                                        .getStaleProcessingSeconds()))));
        manualReview.set(operations.selectCount(new LambdaQueryWrapper<LifecycleOperationEntity>()
                .eq(LifecycleOperationEntity::getStatus, "MANUAL_REVIEW")));
    }

    public void recordJobResult(String jobName, LifecycleJobBatchResult result) {
        increment(jobName, "succeeded", result.succeeded());
        increment(jobName, "failed", result.failed());
        increment(jobName, "exhausted", result.exhausted());
        increment(jobName, "skipped", result.skipped());
    }

    private void increment(String jobName, String result, int count) {
        if (count <= 0) return;
        registry.counter("passenger.lifecycle.job.items",
                "job", jobName, "result", result).increment(count);
    }
}
