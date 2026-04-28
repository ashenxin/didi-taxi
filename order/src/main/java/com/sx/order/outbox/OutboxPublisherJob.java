package com.sx.order.outbox;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.model.OrderOutboxEvent;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class OutboxPublisherJob {

    private final OrderOutboxEventMapper mapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${order.outbox.publisher.batch-size:50}")
    private int batchSize;

    @Value("${order.outbox.publisher.processing-timeout-seconds:300}")
    private int processingTimeoutSeconds;

    public OutboxPublisherJob(OrderOutboxEventMapper mapper, KafkaTemplate<String, String> kafkaTemplate) {
        this.mapper = mapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @XxlJob("orderOutboxPublish")
    public void publish() throws Exception {
        final long startMs = System.currentTimeMillis();
        XxlJobHelper.log("[START] job=orderOutboxPublish batchSize={} processingTimeoutSeconds={}", batchSize, processingTimeoutSeconds);
        int reclaimed = 0;
        int candidatesSize = 0;
        int claimed = 0;
        int published = 0;
        int failed = 0;
        try {
            reclaimed = reclaimStuckProcessing();

            LocalDateTime now = LocalDateTime.now();
            List<OrderOutboxEvent> candidates = mapper.selectList(Wrappers.<OrderOutboxEvent>lambdaQuery()
                    .eq(OrderOutboxEvent::getStatus, "PENDING")
                    .le(OrderOutboxEvent::getNextRetryAt, now)
                    .orderByAsc(OrderOutboxEvent::getId)
                    .last("LIMIT " + Math.max(1, batchSize)));
            if (candidates == null || candidates.isEmpty()) {
                XxlJobHelper.log("no pending outbox events (reclaimed={})", reclaimed);
                return;
            }
            candidatesSize = candidates.size();

            String processingBy = safeHost();

            for (OrderOutboxEvent e : candidates) {
                if (e == null || e.getId() == null) {
                    continue;
                }
                int updated = mapper.update(null, Wrappers.<OrderOutboxEvent>lambdaUpdate()
                        .set(OrderOutboxEvent::getStatus, "PROCESSING")
                        .set(OrderOutboxEvent::getProcessingAt, now)
                        .set(OrderOutboxEvent::getProcessingBy, processingBy)
                        .set(OrderOutboxEvent::getUpdatedAt, now)
                        .eq(OrderOutboxEvent::getId, e.getId())
                        .eq(OrderOutboxEvent::getStatus, "PENDING"));
                if (updated != 1) {
                    continue;
                }
                claimed++;

                try {
                    // key 取 orderNo（aggregateId）
                    kafkaTemplate.send(e.getTopic(), e.getAggregateId(), e.getPayload()).get();

                    mapper.update(null, Wrappers.<OrderOutboxEvent>lambdaUpdate()
                            .set(OrderOutboxEvent::getStatus, "PUBLISHED")
                            .set(OrderOutboxEvent::getUpdatedAt, LocalDateTime.now())
                            .eq(OrderOutboxEvent::getId, e.getId())
                            .eq(OrderOutboxEvent::getStatus, "PROCESSING"));
                    published++;
                } catch (Exception ex) {
                    failed++;
                    String err = ex.toString();
                    if (err.length() > 1800) {
                        err = err.substring(0, 1800);
                    }
                    LocalDateTime next = LocalDateTime.now().plusSeconds(backoffSeconds(e.getRetryCount() == null ? 0 : e.getRetryCount()));
                    mapper.update(null, Wrappers.<OrderOutboxEvent>lambdaUpdate()
                            .set(OrderOutboxEvent::getStatus, "PENDING")
                            .set(OrderOutboxEvent::getRetryCount, (e.getRetryCount() == null ? 0 : e.getRetryCount()) + 1)
                            .set(OrderOutboxEvent::getNextRetryAt, next)
                            .set(OrderOutboxEvent::getLastError, err)
                            .set(OrderOutboxEvent::getUpdatedAt, LocalDateTime.now())
                            .eq(OrderOutboxEvent::getId, e.getId())
                            .eq(OrderOutboxEvent::getStatus, "PROCESSING"));
                    log.warn("outbox publish failed id={} topic={} err={}", e.getId(), e.getTopic(), ex.toString());
                }
            }
        } finally {
            XxlJobHelper.log(
                    "[END] job=orderOutboxPublish reclaimed={} candidates={} claimed={} published={} failed={} elapsedMs={}",
                    reclaimed, candidatesSize, claimed, published, failed, (System.currentTimeMillis() - startMs)
            );
        }
    }

    private int reclaimStuckProcessing() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(Math.max(30, processingTimeoutSeconds));
        // 批量回收：简单起见按时间条件直接回滚（依赖下游幂等兜底重复投递）
        return mapper.update(null, Wrappers.<OrderOutboxEvent>lambdaUpdate()
                .set(OrderOutboxEvent::getStatus, "PENDING")
                .set(OrderOutboxEvent::getNextRetryAt, LocalDateTime.now())
                .set(OrderOutboxEvent::getUpdatedAt, LocalDateTime.now())
                .isNotNull(OrderOutboxEvent::getProcessingAt)
                .lt(OrderOutboxEvent::getProcessingAt, deadline)
                .eq(OrderOutboxEvent::getStatus, "PROCESSING"));
    }

    private static long backoffSeconds(int retryCount) {
        // 1s, 5s, 30s, 2min, 10min（上限）
        return switch (Math.max(0, retryCount)) {
            case 0 -> 1;
            case 1 -> 5;
            case 2 -> 30;
            case 3 -> 120;
            default -> 600;
        };
    }

    private static String safeHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

