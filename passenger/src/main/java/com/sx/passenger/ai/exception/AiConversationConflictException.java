package com.sx.passenger.ai.exception;

/** AI 会话轮次冲突：活动请求占用、幂等请求执行中或幂等键内容不一致。 */
public class AiConversationConflictException extends RuntimeException {

    public static final String AI_REQUEST_IN_PROGRESS = "AI_REQUEST_IN_PROGRESS";
    public static final String AI_REQUEST_PROCESSING = "AI_REQUEST_PROCESSING";
    public static final String AI_REQUEST_EXPIRED = "AI_REQUEST_EXPIRED";
    public static final String AI_IDEMPOTENCY_CONFLICT = "AI_IDEMPOTENCY_CONFLICT";

    private final String code;

    public AiConversationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
