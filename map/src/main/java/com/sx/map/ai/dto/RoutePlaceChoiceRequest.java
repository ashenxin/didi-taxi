package com.sx.map.ai.dto;

/** 仅由已认证乘客会话的编排层提交；地点身份从地图缓存恢复。 */
public record RoutePlaceChoiceRequest(long customerId, String conversationNo,
                                      String taskNo, String placeChoiceId, long conditionVersion,
                                      long choiceRequestVersion, long currentRequestVersion,
                                      String currentRequestNo) {
    public RoutePlaceChoiceRequest(long customerId, String conversationNo,
                                   String taskNo, String placeChoiceId, long conditionVersion,
                                   long choiceRequestVersion, long currentRequestVersion) {
        this(customerId, conversationNo, taskNo, placeChoiceId, conditionVersion,
                choiceRequestVersion, currentRequestVersion, null);
    }
}
