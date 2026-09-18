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

    public record AiTurnFailResponse(String messageNo, String requestNo, long sequenceNo) {
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
