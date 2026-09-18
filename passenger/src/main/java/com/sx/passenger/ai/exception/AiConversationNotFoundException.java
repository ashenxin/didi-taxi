package com.sx.passenger.ai.exception;

/**
 * AI 会话不存在、已删除或归属不匹配。
 * 归属不匹配与不存在对外表现一致，不泄漏资源是否属于他人。
 */
public class AiConversationNotFoundException extends RuntimeException {

    public AiConversationNotFoundException() {
        super("会话不存在");
    }
}
