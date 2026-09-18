package com.sx.passenger.ai.dto;

/** 内部接口创建 AI 客服会话请求。 */
public record AiConversationCreateRequest(long customerId, String idempotencyKey) {
    public AiConversationCreateRequest {
        if (customerId <= 0) {
            throw new IllegalArgumentException("乘客ID必须为正数");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("幂等键不能为空且长度不能超过128");
        }
    }
}
