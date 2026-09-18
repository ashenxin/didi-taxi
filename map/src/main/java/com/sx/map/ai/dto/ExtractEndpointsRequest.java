package com.sx.map.ai.dto;

/** 从乘客文字提取起终点的内部请求。 */
public record ExtractEndpointsRequest(String userText) {
    public ExtractEndpointsRequest {
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("用户文本不能为空");
        }
    }
}
