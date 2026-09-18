package com.sx.passenger.ai.dto;

/** 内部接口开始一轮乘客消息请求。 */
public record AiTurnBeginRequest(long customerId, String idempotencyKey, String content) {
    public AiTurnBeginRequest {
        if (customerId <= 0) {
            throw new IllegalArgumentException("乘客ID必须为正数");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("幂等键不能为空且长度不能超过128");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        content = content.strip();
        if (content.length() > 1000) {
            throw new IllegalArgumentException("消息内容长度不能超过1000");
        }
    }
}
