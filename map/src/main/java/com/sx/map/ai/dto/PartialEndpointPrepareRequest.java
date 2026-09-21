package com.sx.map.ai.dto;

/** 首轮只提供一个起点或终点时，确认并短时保存该地点。 */
public record PartialEndpointPrepareRequest(long customerId, String conversationNo,
                                            String requestNo, long conditionVersion,
                                            String endpointRole, String placeName,
                                            String placeRegion, String intent,
                                            String originalUserText) {
}
