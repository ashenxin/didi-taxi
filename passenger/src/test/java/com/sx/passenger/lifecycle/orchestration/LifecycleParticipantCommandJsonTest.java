package com.sx.passenger.lifecycle.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleParticipantCommandJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void keepsKafkaRoutingFieldsOnTheInternalCommand() throws Exception {
        LifecycleParticipantCommand command = new LifecycleParticipantCommand(
                "event-1", "operation-1", "ORDER_FINAL_CHECK", 10013L, 1L,
                "CANCELLING", "ORDER", LocalDateTime.of(2026, 8, 1, 15, 30));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(command));

        assertThat(json.path("eventId").asText()).isEqualTo("event-1");
        assertThat(json.path("targetDomain").asText()).isEqualTo("ORDER");
        assertThat(json.has("sourceEventId")).isFalse();
    }

    @Test
    void mapsTheInternalCommandToTheHttpParticipantContract() throws Exception {
        LifecycleParticipantCommand command = new LifecycleParticipantCommand(
                "event-1", "operation-1", "ORDER_FINAL_CHECK", 10013L, 1L,
                "CANCELLING", "ORDER", LocalDateTime.of(2026, 8, 1, 15, 30));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(
                LifecycleParticipantHttpCommand.from(command)));

        assertThat(json.path("sourceEventId").asText()).isEqualTo("event-1");
        assertThat(json.has("eventId")).isFalse();
        assertThat(json.has("targetDomain")).isFalse();
        assertThat(json.path("operationNo").asText()).isEqualTo("operation-1");
        assertThat(json.path("stepCode").asText()).isEqualTo("ORDER_FINAL_CHECK");
        assertThat(json.path("customerId").asLong()).isEqualTo(10013L);
        assertThat(json.path("lifecycleVersion").asLong()).isEqualTo(1L);
        assertThat(json.path("targetLifecycleStatus").asText()).isEqualTo("CANCELLING");
        assertThat(json.path("requestedAt").asText()).isEqualTo("2026-08-01T15:30:00");
    }
}
