package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一追加账户生命周期状态投影事件。
 *
 * <p>调用方必须在自身业务事务内调用本组件，使权威状态变更与 Outbox 写入原子提交。
 * Order、Calculate、Wallet 只消费这里定义的稳定载荷，不感知换号或注销的内部步骤。
 */
@Component
public class LifecycleStatusOutboxAppender {
    public static final String EVENT_TYPE = "ACCOUNT_LIFECYCLE_STATUS_CHANGED";

    private final LifecycleOutboxMapper outboxes;
    private final LifecycleIdentifierGenerator identifiers = new UuidLifecycleIdentifierGenerator();
    private final LifecycleJson json = new LifecycleJson();
    private final String eventTopic;

    public LifecycleStatusOutboxAppender(
            LifecycleOutboxMapper outboxes,
            @Value("${passenger.account-lifecycle.messaging.event-topic}") String eventTopic) {
        this.outboxes = outboxes;
        this.eventTopic = eventTopic;
    }

    /**
     * 追加一条携带最新权威状态和版本的待发布事件。
     *
     * @param causalOperationNo 产生本次状态变化的 Operation，用于审计和 Outbox 聚合
     * @param projectedOperationNo 当前仍对账号生效的 Operation；终态 ACTIVE/CANCELLED 必须为空
     */
    public String append(long operationId,
                         String causalOperationNo,
                         String projectedOperationNo,
                         long customerId,
                         long lifecycleVersion,
                         String lifecycleStatus,
                         String causationEventId,
                         String traceId,
                         LocalDateTime updatedAt) {
        return appendStatus(operationId, causalOperationNo, projectedOperationNo,
                customerId, lifecycleVersion,
                lifecycleStatus, causationEventId, traceId, updatedAt);
    }

    /** 新账号注册时追加 ACTIVE/version 0 初始化事件；注册本身不创建 Lifecycle Operation。 */
    public String appendInitialActive(long customerId, LocalDateTime updatedAt) {
        return appendStatus(null, null, null, customerId, 0L, "ACTIVE",
                null, null, updatedAt);
    }

    private String appendStatus(Long operationId,
                                String causalOperationNo,
                                String projectedOperationNo,
                                long customerId,
                                long lifecycleVersion,
                                String lifecycleStatus,
                                String causationEventId,
                                String traceId,
                                LocalDateTime updatedAt) {
        String eventId = identifiers.nextEventId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("operationNo", projectedOperationNo);
        payload.put("customerId", customerId);
        payload.put("lifecycleVersion", lifecycleVersion);
        payload.put("lifecycleStatus", lifecycleStatus);
        payload.put("updatedAt", updatedAt);

        LifecycleOutboxEntity outbox = new LifecycleOutboxEntity()
                .setEventId(eventId).setOperationId(operationId)
                .setAggregateType(operationId == null ? "CUSTOMER_ACCOUNT" : "ACCOUNT_LIFECYCLE")
                .setAggregateId(causalOperationNo == null ? "CUSTOMER:" + customerId : causalOperationNo)
                .setEventType(EVENT_TYPE).setCausationEventId(causationEventId).setTraceId(traceId)
                .setTopic(eventTopic).setPartitionKey(Long.toString(customerId))
                .setPayload(json.write(payload)).setStatus("PENDING")
                .setRetryCount(0).setMaxRetryCount(10).setNextRetryAt(updatedAt)
                .setCreatedAt(updatedAt).setUpdatedAt(updatedAt);
        if (outboxes.insert(outbox) != 1) {
            throw new IllegalStateException("生命周期状态投影事件Outbox写入失败");
        }
        return eventId;
    }
}
