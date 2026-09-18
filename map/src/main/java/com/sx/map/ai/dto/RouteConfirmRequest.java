package com.sx.map.ai.dto;

/** 乘客回复"对"后的确认内部请求。 */
public record RouteConfirmRequest(long customerId,
                                  String conversationNo,
                                  String latestConfirmationRequestNo,
                                  long conditionVersion,
                                  String replyText) {
    public RouteConfirmRequest {
        if (customerId <= 0 || conditionVersion <= 0) {
            throw new IllegalArgumentException("乘客ID与条件版本必须为正数");
        }
        if (conversationNo == null || conversationNo.isBlank()
                || latestConfirmationRequestNo == null || latestConfirmationRequestNo.isBlank()) {
            throw new IllegalArgumentException("会话编号与最新确认提问请求号不能为空");
        }
    }
}
