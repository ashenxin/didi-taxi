package com.sx.passenger.ai.dto;

import java.time.LocalDateTime;

/** 客服消息的可读视图，供编排层判断确认提问与重放历史内容。 */
public record AssistantMessageView(String messageNo,
                                   String requestNo,
                                   String role,
                                   String messageType,
                                   String content,
                                   String payloadJson,
                                   long sequenceNo,
                                   LocalDateTime createdAt,
                                   String status,
                                   String failureCode,
                                   String failureMessage) {
}
