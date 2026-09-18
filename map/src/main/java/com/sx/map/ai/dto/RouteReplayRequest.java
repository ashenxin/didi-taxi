package com.sx.map.ai.dto;

/** 内部重放请求；routeRef 由 BFF 从已归属校验的持久化卡片读取。 */
public record RouteReplayRequest(long customerId, String conversationNo, String routeRef) {
}
