package com.sx.passengerapi.client.dto.maproute;

import java.time.Instant;
import java.util.List;

/**
 * map-service AI 路线内部接口的 DTO 镜像。
 * 仓库惯例：模块间不互相 import，按返回类型由 Jackson 反序列化。
 */
public final class MapRouteDtos {

    private MapRouteDtos() {
    }

    public record ExtractEndpointsRequest(String userText) {
    }

    public record ExtractEndpointsResponse(String originName,
                                           String destinationName,
                                           String originRegion,
                                           String destinationRegion,
                                           String intent) {
    }

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
    }

    public record RoutePreparationResponse(String status,
                                           EndpointFeedbackView originFeedback,
                                           EndpointFeedbackView destinationFeedback,
                                           Instant expiresAt) {
    }

    public record EndpointFeedbackView(String status, List<CandidateView> candidates) {
    }

    public record CandidateView(String name, String type, String city, String district, String address) {
    }

    public record RouteConfirmRequest(long customerId,
                                      String conversationNo,
                                      String latestConfirmationRequestNo,
                                      long conditionVersion,
                                      String replyText) {
    }

    public record RouteConfirmResponse(String status, String originalUserText) {
    }

    public record DefaultRouteRequest(long customerId, String conversationNo, long conditionVersion) {
    }

    public record RouteReplayRequest(long customerId, String conversationNo, String routeRef) {
    }

    public record RouteReplayResponse(RouteCardDto routeCard) {
    }

    public record RouteCardDto(String routeRef,
                               PlaceView origin,
                               PlaceView destination,
                               PlaceView via,
                               RouteView route,
                               Instant generatedAt,
                               Instant expiresAt) {
    }

    public record PlaceView(String name) {
    }

    public record RouteView(String coordinateSystem,
                            long distanceMeters,
                            long durationSeconds,
                            List<GeoPointView> polyline) {
    }

    public record GeoPointView(double longitude, double latitude) {
    }
}
