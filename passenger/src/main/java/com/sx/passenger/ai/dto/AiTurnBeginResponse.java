package com.sx.passenger.ai.dto;

/**
 * 开始一轮乘客消息的结果。
 *
 * requestVersion 即本轮用户消息的会话内序号，作为本会话单调递增的请求版本。
 * latestAssistantMessage 是最近一条客服消息，供编排层判断最新确认提问；
 * assistantReply 是同 requestNo 已持久化的客服回复，非空表示幂等重放（已出过答案）。
 */
public record AiTurnBeginResponse(String requestNo,
                                  long requestVersion,
                                  AssistantMessageView latestAssistantMessage,
                                  AssistantMessageView assistantReply) {
}
