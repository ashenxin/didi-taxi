package com.sx.passenger.ai.dto;

import java.time.LocalDateTime;

/** 完成一轮客服消息的结果。 */
public record AiTurnCompleteResponse(String messageNo,
                                     String requestNo,
                                     long sequenceNo,
                                     LocalDateTime createdAt) {
}
