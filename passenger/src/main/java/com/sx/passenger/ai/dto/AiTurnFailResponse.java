package com.sx.passenger.ai.dto;

/** 标记一轮失败请求的结果。 */
public record AiTurnFailResponse(String messageNo,
                                 String requestNo,
                                 long sequenceNo,
                                 AssistantMessageView assistantReply) {

    public AiTurnFailResponse(String messageNo, String requestNo, long sequenceNo) {
        this(messageNo, requestNo, sequenceNo, null);
    }
}
