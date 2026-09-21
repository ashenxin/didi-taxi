package com.sx.passengerapi.client.dto.maproute;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * map-service AI 路线内部接口的 DTO 镜像。
 * 仓库惯例：模块间不互相 import，按返回类型由 Jackson 反序列化。
 */
public final class MapRouteDtos {

    private MapRouteDtos() {
    }

    public record ExtractEndpointsRequest(String userText, String expectedEndpointRole) {
        public ExtractEndpointsRequest(String userText) {
            this(userText, null);
        }
    }

    public record ExtractEndpointsResponse(String originName,
                                           String destinationName,
                                           String originRegion,
                                           String destinationRegion,
                                           String intent,
                                           String viaPlaceName,
                                           String viaPlaceRegion,
                                           String checkPlaceName,
                                           String checkPlaceRegion,
                                           String checkMode) {

        /** 保留默认路线现有调用和测试的构造方式。 */
        public ExtractEndpointsResponse(String originName, String destinationName,
                                        String originRegion, String destinationRegion,
                                        String intent) {
            this(originName, destinationName, originRegion, destinationRegion, intent,
                    null, null, null, null, null);
        }

        public ExtractEndpointsResponse(String originName, String destinationName,
                                        String originRegion, String destinationRegion,
                                        String intent, String checkPlaceName,
                                        String checkPlaceRegion) {
            this(originName, destinationName, originRegion, destinationRegion, intent,
                    null, null, checkPlaceName, checkPlaceRegion, "UNSPECIFIED");
        }

        public ExtractEndpointsResponse(String originName, String destinationName,
                                        String originRegion, String destinationRegion,
                                        String intent, String checkPlaceName,
                                        String checkPlaceRegion, String checkMode) {
            this(originName, destinationName, originRegion, destinationRegion, intent,
                    null, null, checkPlaceName, checkPlaceRegion, checkMode);
        }
    }

    public record RoutePreparationRequest(long customerId,
                                          String conversationNo,
                                          @JsonProperty("confirmationRequestNo") String requestNo,
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
                                           Instant expiresAt,
                                           PlaceChoicesView placeChoices,
                                           PartialEndpointView partialEndpoint) {
        /** 保留既有默认路线测试与调用方使用的构造方式。 */
        public RoutePreparationResponse(String status, EndpointFeedbackView originFeedback,
                                        EndpointFeedbackView destinationFeedback, Instant expiresAt) {
            this(status, originFeedback, destinationFeedback, expiresAt, null, null);
        }

        public RoutePreparationResponse(String status, EndpointFeedbackView originFeedback,
                                        EndpointFeedbackView destinationFeedback, Instant expiresAt,
                                        PlaceChoicesView placeChoices) {
            this(status, originFeedback, destinationFeedback, expiresAt, placeChoices, null);
        }
    }

    public record PartialEndpointView(long conditionVersion, String followUpRequestNo,
                                      String knownRole, String missingRole,
                                      String knownPlaceName, String intent,
                                      Instant expiresAt) {
    }

    public record PartialEndpointPrepareRequest(long customerId, String conversationNo,
                                                String requestNo, long conditionVersion,
                                                String endpointRole, String placeName,
                                                String placeRegion, String intent,
                                                String originalUserText) {
    }

    public record PartialEndpointCompleteRequest(long customerId, String conversationNo,
                                                 String followUpRequestNo,
                                                 long partialConditionVersion,
                                                 String currentRequestNo,
                                                 long currentConditionVersion,
                                                 String endpointRole, String placeName,
                                                 String placeRegion) {
    }

    public record EndpointFeedbackView(String status, List<CandidateView> candidates) {
    }

    public record CandidateView(String name, String type, String city, String district, String address) {
    }

    /** 与 map-service 的地点选项展示契约一致；不接收 POI ID、坐标或原始地图响应。 */
    public record PlaceChoicesView(String taskNo, String role,
                                   List<PlaceChoiceView> choices, Instant expiresAt) {
    }

    public record PlaceChoiceView(String placeChoiceId, String name, String type,
                                  String city, String address, Instant expiresAt) {
    }

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

    /**
     * 核查本会话当前路线是否经过指定地点。
     * routeRef 只能取自本会话最新持久化的路线卡，不能由模型或客户端自由构造。
     */
    public record CurrentRouteCheckRequest(long customerId, String conversationNo, String routeRef,
                                           long requestVersion, String placeName, String placeRegion,
                                           String checkMode, String originalUserText) {
        public CurrentRouteCheckRequest(long customerId, String conversationNo, String routeRef,
                                        long requestVersion, String placeName, String placeRegion,
                                        String originalUserText) {
            this(customerId, conversationNo, routeRef, requestVersion, placeName, placeRegion,
                    "UNSPECIFIED", originalUserText);
        }
    }

    /** 地点重名时，只携带 map-service 签发的不透明选项编号继续核查。 */
    public record CurrentRouteCheckPlaceChoiceRequest(long customerId, String conversationNo,
                                                      String routeRef, long choiceRequestVersion,
                                                      long currentRequestVersion, String taskNo,
                                                      String placeChoiceId) {
    }

    public record CurrentRouteCheckModeChoiceRequest(long customerId, String conversationNo,
                                                     String routeRef, long choiceRequestVersion,
                                                     long currentRequestVersion, String taskNo,
                                                     String checkMode) {
    }

    public record CurrentRouteCheckResponse(String status, String verdict, String reason, String detail,
                                            CurrentRouteCheckTargetView target,
                                            String checkMode,
                                            CurrentRouteCheckPlaceChoicesView placeChoices,
                                            CurrentRouteCheckModeChoicesView modeChoices) {
        /** 保留现有测试构造方式，新增字段默认为空。 */
        public CurrentRouteCheckResponse(String status, String verdict, String reason, String detail,
                                         CurrentRouteCheckTargetView target,
                                         CurrentRouteCheckPlaceChoicesView placeChoices) {
            this(status, verdict, reason, detail, target, null, placeChoices, null);
        }
    }

    public record CurrentRouteCheckTargetView(String name, String type, String city,
                                              String address, String businessType) {
    }

    public record CurrentRouteCheckPlaceChoicesView(String taskNo, String role,
                                                    List<CurrentRouteCheckPlaceChoiceView> choices,
                                                    Instant expiresAt) {
    }

    /** 展示字段供用户辨认；地图 POI ID 与坐标仍只保存在 map-service。 */
    public record CurrentRouteCheckPlaceChoiceView(String placeChoiceId, String name, String type,
                                                   String city, String address, String businessType,
                                                   Instant expiresAt) {
    }

    public record CurrentRouteCheckModeChoicesView(String taskNo,
                                                   CurrentRouteCheckTargetView target,
                                                   List<CurrentRouteCheckModeChoiceView> choices,
                                                   Instant expiresAt) {
    }

    public record CurrentRouteCheckModeChoiceView(String choiceId, String label) {
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

    public record ViaRouteRequest(long customerId, String conversationNo, String routeRef,
                                  long conditionVersion, long requestVersion,
                                  String placeName, String placeRegion, String originalUserText) {
    }

    public record ViaRoutePlaceChoiceRequest(long customerId, String conversationNo,
                                             long conditionVersion, long choiceRequestVersion,
                                             long currentRequestVersion, String taskNo,
                                             String placeChoiceId) {
    }

    public record ViaRouteResponse(String status, long conditionVersion, String detail,
                                   RouteCardDto routeCard,
                                   ViaRoutePlaceChoicesView placeChoices) {
    }

    public record ViaRoutePlaceChoicesView(String taskNo, String role,
                                           List<ViaRoutePlaceChoiceView> choices,
                                           Instant expiresAt) {
    }

    public record ViaRoutePlaceChoiceView(String placeChoiceId, String name, String type,
                                          String city, String address, String businessType,
                                          Instant expiresAt) {
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
