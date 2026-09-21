package com.sx.passenger.ai.dto;

import java.time.LocalDateTime;

/** 逻辑删除结果；重复删除同一归属会话返回同一删除时间。 */
public record AiConversationDeleteResponse(String conversationNo, LocalDateTime deletedAt) {
}
