package com.sx.map.ai.dto;

/** 缓存或当前条件失效时 routeCard 为 null，由 BFF 使用已落库的展示摘要。 */
public record RouteReplayResponse(RouteCardDto routeCard) {
}
