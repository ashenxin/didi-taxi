package com.sx.map.ai.dto;

/** 生成并缓存默认路线的内部请求。 */
public record DefaultRouteRequest(long customerId, String conversationNo, long conditionVersion) {
    public DefaultRouteRequest {
        if (customerId <= 0 || conditionVersion <= 0) {
            throw new IllegalArgumentException("乘客ID与条件版本必须为正数");
        }
        if (conversationNo == null || conversationNo.isBlank()) {
            throw new IllegalArgumentException("会话编号不能为空");
        }
    }
}
