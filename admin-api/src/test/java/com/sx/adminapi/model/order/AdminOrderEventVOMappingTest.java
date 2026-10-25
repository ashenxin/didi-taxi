package com.sx.adminapi.model.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminOrderEventVOMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * order-service 返回的字段名是 eventPayload；admin-api 对外继续使用 extJson，
     * 两者必须通过 JsonAlias 正确映射，不能让后台事件详情的 extJson 永远为空。
     */
    @Test
    void mapsOrderServiceEventPayloadIntoExtJsonAndKeepsPublicFieldName() throws Exception {
        String payload = "{\"offerRound\":1}";

        AdminOrderEventVO event = objectMapper.convertValue(
                Map.of("eventType", "ORDER_OFFER_OPENED", "eventPayload", payload),
                AdminOrderEventVO.class);

        assertEquals(payload, event.getExtJson());

        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsString(event));
        assertEquals(payload, serialized.path("extJson").asText());
        assertTrue(serialized.path("eventPayload").isMissingNode());
    }
}
