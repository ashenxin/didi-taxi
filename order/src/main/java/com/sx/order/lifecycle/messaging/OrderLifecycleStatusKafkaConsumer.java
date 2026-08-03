package com.sx.order.lifecycle.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "order.lifecycle.messaging",
        name = "enabled", havingValue = "true")
public class OrderLifecycleStatusKafkaConsumer {
    private final ObjectMapper objectMapper;
    private final OrderLifecycleProjectionService projections;

    public OrderLifecycleStatusKafkaConsumer(
            ObjectMapper objectMapper, OrderLifecycleProjectionService projections) {
        this.objectMapper = objectMapper;
        this.projections = projections;
    }

    @KafkaListener(topics = "${order.lifecycle.messaging.event-topic}")
    public void consume(String payload) {
        LifecycleStatusEvent event = read(payload);
        if (event.isInitialActive()) {
            projections.seedActive(event.customerId(), event.eventId(), event.updatedAt());
            return;
        }
        projections.apply(new ApplyOrderLifecycleProjectionCommand(
                event.customerId(), 0, event.lifecycleStatus(), event.lifecycleVersion(),
                event.operationNo(), event.eventId(), event.updatedAt()));
    }

    private LifecycleStatusEvent read(String payload) {
        try {
            return objectMapper.readValue(payload, LifecycleStatusEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Order生命周期状态事件格式错误", ex);
        }
    }

    private record LifecycleStatusEvent(String eventId, String operationNo, long customerId,
                                        long lifecycleVersion, String lifecycleStatus,
                                        LocalDateTime updatedAt) {
        private boolean isInitialActive() {
            return operationNo == null && lifecycleVersion == 0 && "ACTIVE".equals(lifecycleStatus);
        }
    }
}
