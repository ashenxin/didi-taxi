package com.sx.passengerapi.client.dto.ai;

import java.time.LocalDateTime;
import java.util.List;

/**
 * passenger-service AI 会话内部接口的 DTO 镜像。
 * 仓库惯例：模块间不互相 import，按返回类型由 Jackson 反序列化。
 */
public final class AiConversationDtos {

    private AiConversationDtos() {
    }

    public record AiConversationCreateRequest(long customerId, String idempotencyKey) {
    }

    public record AiConversationCreateResponse(String conversationNo) {
    }

    public record AiConversationListResponse(List<ConversationView> conversations,
                                             boolean hasMore,
                                             String nextBeforeConversationNo) {
    }

    public record ConversationView(String conversationNo,
                                   String title,
                                   String status,
                                   LocalDateTime lastMessageAt,
                                   LocalDateTime createdAt) {
    }

    public record AiRequestStatusResponse(String requestNo,
                                          long requestVersion,
                                          String clientMessageNo,
                                          String status,
                                          AssistantMessageView assistantReply) {
    }

    public record AiConversationDeleteResponse(String conversationNo,
                                               LocalDateTime deletedAt) {
    }

    public record AiTurnBeginRequest(long customerId, String idempotencyKey, String content) {
    }

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

    public record AiTurnBeginResponse(String requestNo,
                                      long requestVersion,
                                      AssistantMessageView latestAssistantMessage,
                                      AssistantMessageView assistantReply) {
    }

    public record AiTurnCompleteRequest(long customerId,
                                        String messageType,
                                        String content,
                                        String payloadJson) {
    }

    public record AiTurnCompleteResponse(String messageNo,
                                         String requestNo,
                                         long sequenceNo,
                                         LocalDateTime createdAt) {
    }

    public record AiTurnFailRequest(long customerId, String failureCode, String failureMessage) {
    }

    public record AiTurnFailResponse(String messageNo,
                                     String requestNo,
                                     long sequenceNo,
                                     AssistantMessageView assistantReply) {

        public AiTurnFailResponse(String messageNo, String requestNo, long sequenceNo) {
            this(messageNo, requestNo, sequenceNo, null);
        }
    }

    /** 历史消息分页：乘客消息的 clientMessageNo 用于断流后的本地消息合并。 */
    public record AiMessageListResponse(List<MessageView> messages, boolean hasMore) {
    }

    public record MessageView(String messageNo,
                              String requestNo,
                              String clientMessageNo,
                              String role,
                              String messageType,
                              String content,
                              String payloadJson,
                              long sequenceNo,
                              String status,
                              LocalDateTime createdAt) {
    }
}
