package com.sx.passengerapi.model.ai;

/** 用户从客服给出的结构化“经过”含义中选择一项。 */
public record RouteCheckModeChoiceStreamRequest(String checkMode,
                                                long expectedRequestVersion,
                                                String choiceLabel) {
}
