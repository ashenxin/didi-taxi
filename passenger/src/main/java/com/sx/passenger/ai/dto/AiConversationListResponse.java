package com.sx.passenger.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 当前乘客可见的 AI 客服会话列表，按最近消息时间倒序。 */
public record AiConversationListResponse(List<ConversationView> conversations,
                                         boolean hasMore,
                                         String nextBeforeConversationNo) {

    public record ConversationView(String conversationNo,
                                   String title,
                                   String status,
                                   LocalDateTime lastMessageAt,
                                   LocalDateTime createdAt) {
    }
}
