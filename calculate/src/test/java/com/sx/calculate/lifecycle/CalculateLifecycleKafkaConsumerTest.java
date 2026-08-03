package com.sx.calculate.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sx.calculate.lifecycle.messaging.CalculateLifecycleKafkaConsumer;
import com.sx.calculate.lifecycle.model.ApplyCalculateLifecycleProjectionCommand;
import com.sx.calculate.lifecycle.service.AccountLifecycleCalculateParticipantService;
import com.sx.calculate.lifecycle.service.CalculateLifecycleProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CalculateLifecycleKafkaConsumerTest {

    @Test
    void initialActiveEventCreatesProjectionInsteadOfUsingTransitionPath() {
        CalculateLifecycleProjectionService projections =
                mock(CalculateLifecycleProjectionService.class);
        CalculateLifecycleKafkaConsumer consumer = new CalculateLifecycleKafkaConsumer(
                mock(AccountLifecycleCalculateParticipantService.class),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(KafkaTemplate.class), projections, "account.lifecycle.result.v1");

        consumer.consumeStatusEvent("""
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
        CalculateLifecycleProjectionService projections =
                mock(CalculateLifecycleProjectionService.class);
        CalculateLifecycleKafkaConsumer consumer = new CalculateLifecycleKafkaConsumer(
                mock(AccountLifecycleCalculateParticipantService.class),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(KafkaTemplate.class), projections, "account.lifecycle.result.v1");
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 1, 16, 31);

        consumer.consumeStatusEvent("""
                {"eventId":"phone-change-10014","operationNo":null,"customerId":10014,
                 "lifecycleVersion":3,"lifecycleStatus":"ACTIVE",
                 "updatedAt":"2026-08-01T16:31:00"}
                """);

        verify(projections).apply(new ApplyCalculateLifecycleProjectionCommand(
                10014L, 0, "ACTIVE", 3L, null, "phone-change-10014", updatedAt));
        verifyNoMoreInteractions(projections);
    }
}
