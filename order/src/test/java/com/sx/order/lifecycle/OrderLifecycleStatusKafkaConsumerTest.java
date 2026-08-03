package com.sx.order.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sx.order.lifecycle.messaging.OrderLifecycleStatusKafkaConsumer;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class OrderLifecycleStatusKafkaConsumerTest {

    @Test
    void initialActiveEventCreatesProjectionInsteadOfUsingTransitionPath() {
        OrderLifecycleProjectionService projections = mock(OrderLifecycleProjectionService.class);
        OrderLifecycleStatusKafkaConsumer consumer = new OrderLifecycleStatusKafkaConsumer(
                new ObjectMapper().registerModule(new JavaTimeModule()), projections);

        consumer.consume("""
                {"eventId":"register-10014","operationNo":null,"customerId":10014,
                 "lifecycleVersion":0,"lifecycleStatus":"ACTIVE",
                 "updatedAt":"2026-08-01T16:30:00"}
                """);

        verify(projections).seedActive(10014L, "register-10014",
                LocalDateTime.of(2026, 8, 1, 16, 30));
        verifyNoMoreInteractions(projections);
    }

    @Test
    void completedPhoneChangeAppliesHigherActiveVersionWithNoCurrentOperation() {
        OrderLifecycleProjectionService projections = mock(OrderLifecycleProjectionService.class);
        OrderLifecycleStatusKafkaConsumer consumer = new OrderLifecycleStatusKafkaConsumer(
                new ObjectMapper().registerModule(new JavaTimeModule()), projections);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 1, 16, 31);

        consumer.consume("""
                {"eventId":"phone-change-10014","operationNo":null,"customerId":10014,
                 "lifecycleVersion":3,"lifecycleStatus":"ACTIVE",
                 "updatedAt":"2026-08-01T16:31:00"}
                """);

        verify(projections).apply(new ApplyOrderLifecycleProjectionCommand(
                10014L, 0, "ACTIVE", 3L, null, "phone-change-10014", updatedAt));
        verifyNoMoreInteractions(projections);
    }
}
