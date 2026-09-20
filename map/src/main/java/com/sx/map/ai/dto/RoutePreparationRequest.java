package com.sx.map.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 解析起终点并准备继续路线意图的内部请求。 */
public record RoutePreparationRequest(long customerId,
                                      String conversationNo,
                                      @JsonProperty("confirmationRequestNo")
                                      @JsonAlias("requestNo") String requestNo,
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
                || requestNo == null || requestNo.isBlank()) {
            throw new IllegalArgumentException("会话编号与当前请求号不能为空");
        }
    }
}
