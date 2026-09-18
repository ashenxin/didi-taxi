package com.sx.passenger.ai.dto;

/** 内部接口标记一轮失败请求；客服可读失败说明与稳定错误码落库。 */
public record AiTurnFailRequest(long customerId, String failureCode, String failureMessage) {
    public AiTurnFailRequest {
        if (customerId <= 0) {
            throw new IllegalArgumentException("乘客ID必须为正数");
        }
        if (failureCode == null || failureCode.isBlank() || failureCode.length() > 64) {
            throw new IllegalArgumentException("失败错误码不能为空且长度不能超过64");
        }
        if (failureMessage != null && failureMessage.length() > 512) {
            failureMessage = failureMessage.substring(0, 512);
        }
    }
}
