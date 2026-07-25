package com.sx.passenger.lifecycle.messaging;

import com.sx.passenger.lifecycle.orchestration.LifecycleRetryPolicy;
import com.sx.passenger.lifecycle.job.LifecycleJobBatchResult;
import com.sx.passenger.lifecycle.job.LifecycleJobProperties;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "passenger.account-lifecycle.messaging",
        name = "enabled", havingValue = "true")
@Slf4j
public class KafkaLifecycleOutboxPublisher {
    private final LifecycleOutboxMapper outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final LifecycleJobProperties properties;
    private final String workerId;

    public KafkaLifecycleOutboxPublisher(
            LifecycleOutboxMapper outbox,
            KafkaTemplate<String, String> kafka,
            LifecycleJobProperties properties,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.workerId = hostName() + "-" + UUID.randomUUID();
    }

    public LifecycleJobBatchResult publishBatch() {
        return publishBatch(properties.getOutbox().getBatchSize());
    }

    LifecycleJobBatchResult publishBatch(int limit) {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(
                properties.getOutbox().getBatchDeadlineSeconds());
        LocalDateTime now = LocalDateTime.now();
        List<LifecycleOutboxEntity> candidates =
                outbox.findPublishCandidates(now,
                        now.minusSeconds(properties.getOutbox().getStaleProcessingSeconds()),
                        Math.max(1, limit));
        int claimedCount = 0;
        int published = 0;
        int failed = 0;
        int exhausted = 0;
        int skipped = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (System.nanoTime() >= deadline) {
                skipped += candidates.size() - index;
                break;
            }
            LifecycleOutboxEntity candidate = candidates.get(index);
            Boolean claimed = transactions.execute(status ->
                    outbox.claim(candidate.getId(), LocalDateTime.now(),
                            LocalDateTime.now().minusSeconds(properties.getOutbox()
                                    .getStaleProcessingSeconds()), workerId) == 1);
            if (!Boolean.TRUE.equals(claimed)) {
                skipped++;
                continue;
            }
            claimedCount++;
            try {
                kafka.send(candidate.getTopic(), candidate.getPartitionKey(),
                        candidate.getPayload()).get(
                                properties.getOutbox().getSendTimeoutSeconds(),
                                TimeUnit.SECONDS);
                transactions.executeWithoutResult(status -> markPublished(candidate.getId()));
                published++;
            } catch (Exception ex) {
                failed++;
                Boolean retryExhausted = transactions.execute(status ->
                        markFailed(candidate.getId(), safeMessage(ex)));
                if (Boolean.TRUE.equals(retryExhausted)) {
                    exhausted++;
                    log.error("生命周期Outbox发布重试耗尽 eventId={} topic={} retryCount={}",
                            candidate.getEventId(), candidate.getTopic(),
                            candidate.getRetryCount());
                } else {
                    log.warn("生命周期Outbox发布失败 eventId={} topic={} retryCount={} errorType={}",
                            candidate.getEventId(), candidate.getTopic(),
                            candidate.getRetryCount(), ex.getClass().getSimpleName());
                }
            }
        }
        return new LifecycleJobBatchResult(
                candidates.size(), claimedCount, published, failed, exhausted, skipped,
                elapsedMs(started));
    }

    private void markPublished(long id) {
        LifecycleOutboxEntity current = outbox.selectById(id);
        if (current == null || !"PROCESSING".equals(current.getStatus())
                || !workerId.equals(current.getProcessingBy())) return;
        LocalDateTime now = LocalDateTime.now();
        current.setStatus("PUBLISHED").setPublishedAt(now).setProcessingAt(null)
                .setProcessingBy(null).setLastError(null).setUpdatedAt(now);
        if (outbox.updateById(current) != 1) {
            throw new IllegalStateException("生命周期Outbox发布状态更新失败");
        }
    }

    private boolean markFailed(long id, String error) {
        LifecycleOutboxEntity current = outbox.selectById(id);
        if (current == null || !"PROCESSING".equals(current.getStatus())
                || !workerId.equals(current.getProcessingBy())) return false;
        LocalDateTime now = LocalDateTime.now();
        int retries = current.getRetryCount() + 1;
        boolean exhausted = retries >= current.getMaxRetryCount();
        current.setStatus("FAILED").setRetryCount(retries)
                .setNextRetryAt(now.plus(LifecycleRetryPolicy.delay(5, retries)))
                .setProcessingAt(null).setProcessingBy(null).setLastError(error).setUpdatedAt(now);
        if (outbox.updateById(current) != 1) {
            throw new IllegalStateException("生命周期Outbox失败状态更新失败");
        }
        return exhausted;
    }

    private static String safeMessage(Exception ex) {
        String value = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
