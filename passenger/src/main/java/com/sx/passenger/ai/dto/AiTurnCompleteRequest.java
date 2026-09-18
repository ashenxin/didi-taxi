package com.sx.passenger.ai.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 内部接口完成一轮客服消息请求；payloadJson 为空或合法 JSON。 */
public record AiTurnCompleteRequest(long customerId,
                                    String messageType,
                                    String content,
                                    String payloadJson) {
    public AiTurnCompleteRequest {
        if (customerId <= 0) {
            throw new IllegalArgumentException("乘客ID必须为正数");
        }
        if (messageType == null || messageType.isBlank() || messageType.length() > 32) {
            throw new IllegalArgumentException("消息类型不能为空且长度不能超过32");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        content = content.strip();
        if (content.length() > 4000) {
            throw new IllegalArgumentException("消息内容长度不能超过4000");
        }
        payloadJson = normalizePayloadJson(payloadJson);
    }

    private static String normalizePayloadJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(payloadJson);
            if (node == null || node.isNull()) {
                return null;
            }
            return node.toString();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payloadJson 必须是合法JSON");
        }
    }
}
