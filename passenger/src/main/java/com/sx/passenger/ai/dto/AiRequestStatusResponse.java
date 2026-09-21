package com.sx.passenger.ai.dto;

/** 断流恢复所需的单轮请求状态；最终回复沿用已持久化客服消息结构。 */
public record AiRequestStatusResponse(String requestNo,
                                      long requestVersion,
                                      String clientMessageNo,
                                      String status,
                                      AssistantMessageView assistantReply) {
}
