package com.sx.passengerapi.model.ai;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 客服流式轮次的 SSE 事件载荷，事件顺序遵循 API 契约：
 * turn.started → 内容事件 → turn.completed，失败发送 turn.failed。
 */
public final class AiSseEvents {

    private AiSseEvents() {
    }

    /** 受理后最先发送；requestVersion 即本轮用户消息的会话内序号。 */
    public record TurnStartedEvent(String requestNo, long requestVersion, String content) {
    }

    /** 已持久化的客服消息视图；payload 按消息类型给出。 */
    public record AssistantMessageEvent(String messageNo,
                                        String requestNo,
                                        String role,
                                        String messageType,
                                        String content,
                                        Map<String, Object> payload,
                                        LocalDateTime createdAt) {
    }

    /** 一轮一个内容事件：answer.completed 或 route.card。 */
    public record ContentEvent(String requestNo, AssistantMessageEvent assistantMessage) {
    }

    public record TurnCompletedEvent(String requestNo) {
    }

    /** requestNo 可为空：begin 失败时还没有服务端请求号。 */
    public record TurnFailedEvent(String requestNo, String code, String message) {
    }
}
