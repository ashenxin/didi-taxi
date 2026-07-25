package com.sx.passenger.lifecycle.job;

import com.sx.passenger.lifecycle.messaging.KafkaLifecycleOutboxPublisher;
import com.sx.passenger.lifecycle.metrics.LifecycleOrchestrationMetrics;
import com.sx.passenger.lifecycle.orchestration.LifecycleRecoveryService;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AccountLifecycleJobs {
    private final LifecycleRecoveryService recovery;
    private final ObjectProvider<KafkaLifecycleOutboxPublisher> publisher;
    private final LifecycleOrchestrationMetrics metrics;
    private final LifecycleJobReporter reporter;

    public AccountLifecycleJobs(
            LifecycleRecoveryService recovery,
            ObjectProvider<KafkaLifecycleOutboxPublisher> publisher,
            LifecycleOrchestrationMetrics metrics,
            LifecycleJobReporter reporter) {
        this.recovery = recovery;
        this.publisher = publisher;
        this.metrics = metrics;
        this.reporter = reporter;
    }

    @XxlJob("accountLifecycleOutboxPublishJob")
    public void publishOutbox() {
        reporter.execute("outbox", () -> {
            KafkaLifecycleOutboxPublisher active = publisher.getIfAvailable();
            if (active == null) {
                throw new IllegalStateException(
                        "生命周期Kafka已关闭，Outbox发布任务不可执行");
            }
            return active.publishBatch();
        });
    }

    @XxlJob("accountLifecycleRecoveryJob")
    public void recover() {
        reporter.execute("recovery", recovery::recover);
    }

    @XxlJob("accountLifecycleDiagnosticsJob")
    public void diagnostics() {
        reporter.execute("diagnostics", () -> {
            long started = System.nanoTime();
            metrics.refresh();
            return LifecycleJobBatchResult.success(
                    1, Math.max(0, (System.nanoTime() - started) / 1_000_000));
        });
    }
}
