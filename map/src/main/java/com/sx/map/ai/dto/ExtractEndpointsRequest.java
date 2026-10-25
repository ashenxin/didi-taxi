package com.sx.map.ai.dto;

/**
 * 从乘客文字提取意图、地点和天气字段的内部请求。
 *
 * `expectedEndpointRole` 用于单端点续接；`weatherContinuation` 表示上一轮是天气回答，
 * 这是给模型的受控上下文提示，不是完整聊天历史。
 */
public record ExtractEndpointsRequest(String userText, String expectedEndpointRole,
                                      boolean weatherContinuation) {
    public ExtractEndpointsRequest(String userText) {
        this(userText, null, false);
    }

    public ExtractEndpointsRequest(String userText, String expectedEndpointRole) {
        this(userText, expectedEndpointRole, false);
    }

    public ExtractEndpointsRequest {
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("用户文本不能为空");
        }
        if (expectedEndpointRole != null
                && !"ORIGIN".equals(expectedEndpointRole)
                && !"DESTINATION".equals(expectedEndpointRole)) {
            throw new IllegalArgumentException("待补齐端点角色无效");
        }
    }
}
