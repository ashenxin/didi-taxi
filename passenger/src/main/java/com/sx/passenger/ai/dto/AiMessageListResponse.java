package com.sx.passenger.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 历史消息分页结果；messages 按会话内序号升序，hasMore 表示还有更早消息。 */
public record AiMessageListResponse(List<MessageView> messages, boolean hasMore) {

    /**
     * 历史消息视图。payloadJson 是当时持久化的展示摘要（路线卡不含完整折线），
     * status 为 FAILED 的消息表示当时轮次失败的客服说明。
     * clientMessageNo 仅在乘客消息中返回，用于断流后与客户端本地消息精确合并。
     */
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
