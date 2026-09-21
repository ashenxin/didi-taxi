package com.sx.map.ai.dto;

/** 从乘客文字提取起终点的内部请求；续接时可指定本轮必须补齐的端点角色。 */
public record ExtractEndpointsRequest(String userText, String expectedEndpointRole) {
    public ExtractEndpointsRequest(String userText) {
        this(userText, null);
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
