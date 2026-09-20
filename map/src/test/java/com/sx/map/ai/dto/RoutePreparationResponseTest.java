package com.sx.map.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sx.map.ai.dto.RoutePreparationResponse.CandidateView;
import com.sx.map.ai.dto.RoutePreparationResponse.EndpointFeedbackView;
import com.sx.map.ai.dto.RoutePreparationResponse.PlaceChoiceView;
import com.sx.map.ai.dto.RoutePreparationResponse.PlaceChoicesView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutePreparationResponseTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void placeChoiceResponseSerializesOnlyDisplayFieldsAndOpaqueIds() throws Exception {
        Instant expiresAt = Instant.parse("2026-09-18T13:00:00Z");
        RoutePreparationResponse response = new RoutePreparationResponse(
                "NEEDS_PLACE_CHOICE",
                new EndpointFeedbackView("NEEDS_CLARIFICATION", List.of(
                        new CandidateView("德清", "交通设施", "湖州", "德清县", "武康街道"))),
                new EndpointFeedbackView("UNIQUE", List.of(
                        new CandidateView("杭州东站", null, "杭州", null, "天城路"))),
                expiresAt,
                new PlaceChoicesView("AIT-example", "ORIGIN", List.of(
                        new PlaceChoiceView("PC-example", "德清", "交通设施",
                                "湖州", "武康街道", expiresAt)), expiresAt));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(json.path("placeChoices").path("taskNo").asText()).isEqualTo("AIT-example");
        assertThat(json.path("placeChoices").path("role").asText()).isEqualTo("ORIGIN");
        assertThat(json.path("placeChoices").path("choices").get(0).path("placeChoiceId").asText())
                .isEqualTo("PC-example");
        assertThat(json.path("placeChoices").path("choices").get(0).path("city").asText())
                .isEqualTo("湖州");
        assertThat(json.toString()).doesNotContain("placeId", "coordinate", "longitude", "latitude");
    }

    @Test
    void preparationResponseWithoutChoicesStillDeserializes() throws Exception {
        RoutePreparationResponse response = mapper.readValue("""
                {"status":"NEEDS_CLARIFICATION","originFeedback":{"status":"NOT_FOUND","candidates":[]},
                 "destinationFeedback":{"status":"UNIQUE","candidates":[]},"expiresAt":null}
                """, RoutePreparationResponse.class);

        assertThat(response.placeChoices()).isNull();
        assertThat(response.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(new RoutePreparationResponse("READY_FOR_ROUTE",
                response.originFeedback(), response.destinationFeedback(), Instant.now()).placeChoices())
                .isNull();
    }

    @Test
    void endpointChoicesRejectWaypointRoleAndUnidentifiableItems() {
        Instant expiresAt = Instant.parse("2026-09-18T13:00:00Z");
        PlaceChoiceView valid = new PlaceChoiceView("PC-1", "德清", null,
                "湖州", null, expiresAt);

        assertThatThrownBy(() -> new PlaceChoicesView("AIT-1", "WAYPOINT", List.of(valid), expiresAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaceChoiceView("PC-2", "德清", null,
                null, null, expiresAt))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
