package com.sx.map.ai.dto;

/** 下一轮补齐另一端；旧请求号和旧条件版本仅用于恢复可信单端点。 */
public record PartialEndpointCompleteRequest(long customerId, String conversationNo,
                                             String followUpRequestNo, long partialConditionVersion,
                                             String currentRequestNo, long currentConditionVersion,
                                             String endpointRole, String placeName,
                                             String placeRegion) {
}
