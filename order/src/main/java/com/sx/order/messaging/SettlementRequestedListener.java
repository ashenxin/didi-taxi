package com.sx.order.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.order.service.TripOrderSettlementService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SettlementRequestedListener {

    private final ObjectMapper objectMapper;
    private final TripOrderSettlementService settlementService;

    public SettlementRequestedListener(ObjectMapper objectMapper,
                                       TripOrderSettlementService settlementService) {
        this.objectMapper = objectMapper;
        this.settlementService = settlementService;
    }

    @KafkaListener(
            topics = "${order.settlement.topic:order.settlement.requested.v1}",
            groupId = "${order.settlement.consumer-group:order-settlement-v1}",
            autoStartup = "${order.settlement.listener-enabled:true}")
    public void onMessage(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String orderNo = root.path("orderNo").asText();
            if (orderNo.isBlank()) {
                throw new IllegalArgumentException("结算事件缺少orderNo");
            }
            settlementService.process(orderNo);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("结算事件格式不合法", e);
        }
    }
}
