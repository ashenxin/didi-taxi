package com.sx.passenger.lifecycle.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleStatusOutboxAppenderTest {

    @Test
    void keepsCausalOperationForAuditButClearsCompletedOperationFromProjection() throws Exception {
        LifecycleOutboxMapper outboxes = mock(LifecycleOutboxMapper.class);
        when(outboxes.insert(any(LifecycleOutboxEntity.class))).thenReturn(1);
        LifecycleStatusOutboxAppender appender = new LifecycleStatusOutboxAppender(
                outboxes, "account.lifecycle.event.v1");
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 16, 30);

        appender.append(7L, "ALO-phone-change", null, 10014L, 3L,
                "ACTIVE", "EVT-cause", "trace-1", now);

        ArgumentCaptor<LifecycleOutboxEntity> captor =
                ArgumentCaptor.forClass(LifecycleOutboxEntity.class);
        verify(outboxes).insert(captor.capture());
        LifecycleOutboxEntity outbox = captor.getValue();
        assertThat(outbox.getOperationId()).isEqualTo(7L);
        assertThat(outbox.getAggregateType()).isEqualTo("ACCOUNT_LIFECYCLE");
        assertThat(outbox.getAggregateId()).isEqualTo("ALO-phone-change");
        assertThat(outbox.getCausationEventId()).isEqualTo("EVT-cause");

        JsonNode payload = new ObjectMapper().readTree(outbox.getPayload());
        assertThat(payload.path("operationNo").isNull()).isTrue();
        assertThat(payload.path("lifecycleStatus").asText()).isEqualTo("ACTIVE");
        assertThat(payload.path("lifecycleVersion").asLong()).isEqualTo(3L);
    }

    @Test
    void initialActiveEventDoesNotPretendRegistrationIsLifecycleOperation() throws Exception {
        LifecycleOutboxMapper outboxes = mock(LifecycleOutboxMapper.class);
        when(outboxes.insert(any(LifecycleOutboxEntity.class))).thenReturn(1);
        LifecycleStatusOutboxAppender appender = new LifecycleStatusOutboxAppender(
                outboxes, "account.lifecycle.event.v1");
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 16, 30);

        String eventId = appender.appendInitialActive(10014L, now);

        ArgumentCaptor<LifecycleOutboxEntity> captor =
                ArgumentCaptor.forClass(LifecycleOutboxEntity.class);
        verify(outboxes).insert(captor.capture());
        LifecycleOutboxEntity outbox = captor.getValue();
        assertThat(outbox.getEventId()).isEqualTo(eventId);
        assertThat(outbox.getOperationId()).isNull();
        assertThat(outbox.getAggregateType()).isEqualTo("CUSTOMER_ACCOUNT");
        assertThat(outbox.getAggregateId()).isEqualTo("CUSTOMER:10014");
        assertThat(outbox.getEventType()).isEqualTo("ACCOUNT_LIFECYCLE_STATUS_CHANGED");
        assertThat(outbox.getPartitionKey()).isEqualTo("10014");
        assertThat(outbox.getStatus()).isEqualTo("PENDING");

        JsonNode payload = new ObjectMapper().readTree(outbox.getPayload());
        assertThat(payload.path("eventId").asText()).isEqualTo(eventId);
        assertThat(payload.path("customerId").asLong()).isEqualTo(10014L);
        assertThat(payload.path("lifecycleVersion").asLong()).isZero();
        assertThat(payload.path("lifecycleStatus").asText()).isEqualTo("ACTIVE");
        assertThat(payload.path("operationNo").isNull()).isTrue();
    }
}
