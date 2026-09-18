package com.sx.map.ai.dto;

/** 准备待确认起终点的内部请求；intent 为编排识别的业务意图，主干只支持 DEFAULT_ROUTE。 */
public record RoutePreparationRequest(long customerId,
                                      String conversationNo,
                                      String confirmationRequestNo,
                                      long conditionVersion,
                                      String originName,
                                      String originRegion,
                                      String destinationName,
                                      String destinationRegion,
                                      String intent,
                                      String originalUserText) {
    public RoutePreparationRequest {
        if (customerId <= 0 || conditionVersion <= 0) {
            throw new IllegalArgumentException("乘客ID与条件版本必须为正数");
        }
        if (conversationNo == null || conversationNo.isBlank()
                || confirmationRequestNo == null || confirmationRequestNo.isBlank()) {
            throw new IllegalArgumentException("会话编号与确认提问请求号不能为空");
        }
    }
}
