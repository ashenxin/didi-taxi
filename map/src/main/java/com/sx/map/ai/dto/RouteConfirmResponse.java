package com.sx.map.ai.dto;

/** 确认结果；status 为 CONFIRMED / NOT_ACCEPTED / CONTEXT_UNAVAILABLE。 */
public record RouteConfirmResponse(String status, String originalUserText) {
}
