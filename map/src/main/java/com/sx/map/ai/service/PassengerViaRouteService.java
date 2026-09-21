package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.PassengerRouteSnapshot.BusinessType;
import com.sx.map.ai.model.PassengerRouteSnapshot.GeoPoint;
import com.sx.map.ai.model.PassengerRouteSnapshot.NavigationStep;
import com.sx.map.ai.model.PassengerRouteSnapshot.PassageVerification;
import com.sx.map.ai.model.PassengerRouteSnapshot.Place;
import com.sx.map.ai.model.PassengerRouteSnapshot.RouteKind;
import com.sx.map.ai.model.PassengerRouteSnapshot.ViaPlace;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceRole;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceSummary;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceTask;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.PlaceCandidate;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.SelectedChoice;
import com.sx.map.ai.tool.PassengerRoutePoiTools;
import com.sx.map.exception.AmapApiException;
import com.sx.map.exception.AmapRouteEvidenceInsufficientException;
import com.sx.map.exception.AmapRouteNotFoundException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.PassengerRouteCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.service.WaypointRoutePlanningService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 编排乘客指定单个途经地点的新路线。
 *
 * 起终点只能来自当前有效 routeRef 或 map-service 已确认条件；途经地点只来自高德查询或
 * 本服务保存的短时地点选项。模型和客户端不能提交坐标、POI ID、业务类型或经过结论。
 */
@Service
public class PassengerViaRouteService {
    private static final int MAX_CHOICES = 10;
    private static final int MAX_USER_TEXT_LENGTH = 1000;
    private static final String AMAP_PROVIDER = "gaode";

    private final PassengerRouteSnapshotCache snapshotCache;
    private final PassengerRouteConditionsCache conditionsCache;
    private final PassengerRoutePoiTools poiTools;
    private final PassengerRoutePlaceChoiceCache placeChoiceCache;
    private final WaypointRoutePlanningService routePlanningService;

    public PassengerViaRouteService(PassengerRouteSnapshotCache snapshotCache,
                                    PassengerRouteConditionsCache conditionsCache,
                                    PassengerRoutePoiTools poiTools,
                                    PassengerRoutePlaceChoiceCache placeChoiceCache,
                                    WaypointRoutePlanningService routePlanningService) {
        this.snapshotCache = snapshotCache;
        this.conditionsCache = conditionsCache;
        this.poiTools = poiTools;
        this.placeChoiceCache = placeChoiceCache;
        this.routePlanningService = routePlanningService;
    }

    /** routeRef 与 conditionVersion 二选一；前者用于基于当前路线修改，后者用于首轮完整条件。 */
    public Result plan(long customerId,
                       String conversationNo,
                       String routeRef,
                       long conditionVersion,
                       long requestVersion,
                       String placeName,
                       String placeRegion,
                       String originalUserText) {
        if (customerId <= 0 || conversationNo == null || conversationNo.isBlank()
                || requestVersion <= 0) {
            throw new IllegalArgumentException("指定途经点规划缺少可信会话或请求版本");
        }
        String userText = requiredUserText(originalUserText);
        RouteContext context = routeContext(customerId, conversationNo, routeRef, conditionVersion);
        if (context == null) {
            return Result.contextUnavailable();
        }

        List<AmapPoiCandidate> rawCandidates = poiTools.searchNamedWaypointCandidates(placeName, placeRegion);
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return Result.placeNotFound(context.conditionVersion());
        }
        List<Candidate> candidates = rawCandidates.stream()
                .map(this::trustedCandidate)
                .filter(Objects::nonNull)
                .toList();
        if (candidates.isEmpty()) {
            return Result.placeUnverifiable(context.conditionVersion(),
                    "地图结果不属于收费站、服务区或加油站，或缺少可用于算路的位置");
        }
        if (candidates.size() > MAX_CHOICES) {
            return Result.placeUnverifiable(context.conditionVersion(),
                    "同名地点过多，请补充城市或更具体的位置");
        }

        List<Candidate> exactMatches = candidates.stream()
                .filter(candidate -> samePlaceName(placeName, candidate.raw().getName()))
                .toList();
        // 只有唯一且名称完全一致的地图地点可以直接规划；同名多个结果也必须让乘客选择，
        // 不能因为业务类型相同就静默挑选其中一处。
        if (exactMatches.size() == 1) {
            return planVerified(refresh(context), requestVersion,
                    List.of(exactMatches.getFirst().raw()));
        }

        RouteContext latest = refresh(context);
        if (latest == null) {
            return Result.contextUnavailable();
        }
        ChoiceTask task = placeChoiceCache.save(customerId, conversationNo,
                latest.conditionVersion(), requestVersion, ChoiceRole.WAYPOINT,
                PassengerRouteIntent.VIA_ROUTE_PLAN, userText,
                latest.origin(), latest.destination(),
                (exactMatches.isEmpty() ? candidates : exactMatches).stream()
                        .map(Candidate::cached).toList());
        return Result.placeChoices(latest.conditionVersion(), task.taskNo(),
                task.summaries(), task.expiresAt());
    }

    /** 从服务端短时任务恢复地点身份；客户端只能回传不透明选项编号。 */
    public Result planSelected(long customerId,
                               String conversationNo,
                               long conditionVersion,
                               long choiceRequestVersion,
                               long currentRequestVersion,
                               String taskNo,
                               String placeChoiceId) {
        if (currentRequestVersion <= choiceRequestVersion) {
            return Result.contextUnavailable();
        }
        SelectedChoice selected = placeChoiceCache.select(taskNo, placeChoiceId, customerId,
                conversationNo, conditionVersion, choiceRequestVersion).orElse(null);
        if (selected == null || selected.role() != ChoiceRole.WAYPOINT
                || selected.intent() != PassengerRouteIntent.VIA_ROUTE_PLAN) {
            return Result.contextUnavailable();
        }
        PassengerRouteConditionsCache.ConfirmedConditions confirmed = conditionsCache
                .find(customerId, conversationNo, conditionVersion).orElse(null);
        if (confirmed == null || !confirmed.origin().equals(selected.origin())
                || !confirmed.destination().equals(selected.destination())) {
            return Result.contextUnavailable();
        }
        RouteContext context = new RouteContext(customerId, conversationNo, conditionVersion,
                confirmed.origin(), confirmed.destination(), confirmed.expiresAt(), null);
        return planVerified(context, currentRequestVersion,
                List.of(toRawCandidate(selected.candidate())));
    }

    private Result planVerified(RouteContext context,
                                long requestVersion,
                                List<AmapPoiCandidate> candidates) {
        if (context == null) {
            return Result.contextUnavailable();
        }
        List<PassengerRouteCandidate> routes;
        try {
            routes = routePlanningService.planCandidatesFromAmapCoordinates(
                    toPoint(context.origin().coordinate()), toPoint(context.destination().coordinate()),
                    candidates, poiType(candidates));
        } catch (AmapRouteEvidenceInsufficientException e) {
            return Result.placeUnverifiable(context.conditionVersion(), e.getMessage());
        } catch (AmapRouteNotFoundException e) {
            return Result.routeNotFound(context.conditionVersion());
        }
        if (routes == null || routes.isEmpty()) {
            return Result.routeNotFound(context.conditionVersion());
        }
        RouteContext latest = refresh(context);
        if (latest == null) {
            return Result.contextUnavailable();
        }
        PassengerRouteSnapshot snapshot;
        try {
            snapshot = toSnapshot(latest, routes.getFirst());
        } catch (AmapRouteEvidenceInsufficientException e) {
            return Result.placeUnverifiable(latest.conditionVersion(), e.getMessage());
        }
        snapshotCache.save(snapshot);
        return Result.routeReady(requestVersion, latest.conditionVersion(), snapshot);
    }

    /** 同一批直接规划候选必须属于同一业务类型，避免一个请求混合不同途经语义。 */
    private String poiType(List<AmapPoiCandidate> candidates) {
        BusinessType type = null;
        for (AmapPoiCandidate candidate : candidates) {
            BusinessType current = poiTools.waypointBusinessType(candidate);
            if (current == null || type != null && current != type) {
                throw new AmapApiException("指定地点候选的业务类型不一致，请选择具体地点");
            }
            type = current;
        }
        if (type == null) {
            throw new AmapApiException("指定地点类型无法确认");
        }
        return type.name();
    }

    private RouteContext routeContext(long customerId, String conversationNo,
                                      String routeRef, long conditionVersion) {
        if (routeRef != null && !routeRef.isBlank()) {
            PassengerRouteSnapshot current = snapshotCache
                    .findForReplay(routeRef, customerId, conversationNo).orElse(null);
            return current == null ? null : new RouteContext(customerId, conversationNo,
                    current.conditionVersion(), current.origin(), current.destination(),
                    current.expiresAt(), current.routeRef());
        }
        PassengerRouteConditionsCache.ConfirmedConditions confirmed = conditionsCache
                .find(customerId, conversationNo, conditionVersion).orElse(null);
        return confirmed == null ? null : new RouteContext(customerId, conversationNo,
                confirmed.conditionVersion(), confirmed.origin(), confirmed.destination(),
                confirmed.expiresAt(), null);
    }

    private RouteContext refresh(RouteContext context) {
        if (context == null) {
            return null;
        }
        PassengerRouteConditionsCache.ConfirmedConditions confirmed = conditionsCache
                .find(context.customerId(), context.conversationNo(), context.conditionVersion())
                .orElse(null);
        if (confirmed == null || !confirmed.origin().equals(context.origin())
                || !confirmed.destination().equals(context.destination())) {
            return null;
        }
        Instant expiresAt = confirmed.expiresAt().isBefore(context.expiresAt())
                ? confirmed.expiresAt() : context.expiresAt();
        if (context.sourceRouteRef() != null) {
            PassengerRouteSnapshot source = snapshotCache.find(context.sourceRouteRef(),
                    context.customerId(), context.conversationNo(), context.conditionVersion()).orElse(null);
            if (source == null || !source.origin().equals(context.origin())
                    || !source.destination().equals(context.destination())) {
                return null;
            }
            if (source.expiresAt().isBefore(expiresAt)) {
                expiresAt = source.expiresAt();
            }
        }
        return expiresAt.isAfter(Instant.now()) ? new RouteContext(
                context.customerId(), context.conversationNo(), context.conditionVersion(),
                context.origin(), context.destination(), expiresAt, context.sourceRouteRef()) : null;
    }

    private PassengerRouteSnapshot toSnapshot(RouteContext context, PassengerRouteCandidate candidate) {
        RouteResponse route = candidate.route();
        if (route == null || !AMAP_PROVIDER.equals(route.getProvider())
                || route.getDistanceMeters() == null || route.getDurationSeconds() == null
                || route.getDistanceMeters() < 0 || route.getDurationSeconds() < 0) {
            throw new AmapApiException("指定途经点路线缺少可信地图来源、里程或预计时间");
        }
        Candidate trusted = trustedCandidate(candidate.waypoint());
        if (trusted == null) {
            throw new AmapApiException("指定途经地点缺少可信地图身份");
        }
        List<GeoPoint> polyline = toGeoPoints(route.getPolyline(), "完整路线折线");
        if (polyline.size() < 2) {
            throw new AmapApiException("指定途经点路线缺少完整折线");
        }
        List<NavigationStep> navigationSteps = candidate.navigationSteps().stream()
                .map(step -> new NavigationStep(step.instruction(), step.action(),
                        step.assistantAction(), step.orientation(), step.roadName(),
                        toGeoPoints(step.polyline(), "导航步骤折线")))
                .toList();
        if (navigationSteps.isEmpty()) {
            throw new AmapApiException("指定途经点路线缺少导航步骤");
        }
        PassengerRouteEndpointDirectionValidator.validate(context.origin(), context.destination(),
                new com.sx.map.service.AmapDrivingRouteService.DrivingRouteOption(
                        route, candidate.navigationSteps().stream()
                        .map(step -> new com.sx.map.service.AmapDrivingRouteService.NavigationStep(
                                step.instruction(), step.action(), step.assistantAction(),
                                step.orientation(), step.roadName(), step.polyline()))
                        .toList()));
        Instant expiresAt = candidate.expiresAt().isBefore(context.expiresAt())
                ? candidate.expiresAt() : context.expiresAt();
        if (!expiresAt.isAfter(candidate.generatedAt())) {
            throw new AmapApiException("指定途经点路线生成时上下文已失效");
        }
        return new PassengerRouteSnapshot(snapshotCache.newRouteRef(), context.customerId(),
                context.conversationNo(), context.conditionVersion(), context.origin(), context.destination(),
                route.getProvider(), PassengerRouteSnapshot.COORDINATE_SYSTEM_GCJ02,
                PassengerRouteSnapshot.TRAVEL_MODE_DRIVING, RouteKind.VIA_PLACE,
                polyline, navigationSteps, route.getDistanceMeters(), route.getDurationSeconds(),
                candidate.generatedAt(), expiresAt,
                new ViaPlace(trusted.cached().place(), trusted.cached().businessType(),
                        trusted.cached().navigationPoints()),
                toGeoPoints(candidate.routingWaypoints(), "途经点算路坐标"),
                new PassageVerification("PASSED", candidate.passageVerification()));
    }

    private Candidate trustedCandidate(AmapPoiCandidate raw) {
        BusinessType businessType = poiTools.waypointBusinessType(raw);
        if (raw == null || !hasText(raw.getName()) || !validPoint(raw.getCenterPoint())
                || !hasText(raw.getCity()) && !hasText(raw.getAddress()) || businessType == null) {
            return null;
        }
        List<GeoPoint> navigationPoints = new ArrayList<>(2);
        if (validPoint(raw.getEntrancePoint())) {
            navigationPoints.add(toGeoPoint(raw.getEntrancePoint()));
        }
        if (validPoint(raw.getExitPoint())) {
            navigationPoints.add(toGeoPoint(raw.getExitPoint()));
        }
        if (businessType != BusinessType.TOLL_STATION && navigationPoints.isEmpty()) {
            return null;
        }
        try {
            Place place = new Place(blankToNull(raw.getPoiId()), raw.getName().strip(),
                    blankToNull(raw.getAddress()), blankToNull(raw.getCity()),
                    toGeoPoint(raw.getCenterPoint()));
            return new Candidate(raw, new PlaceCandidate(place, businessType, navigationPoints,
                    blankToNull(raw.getType())));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AmapPoiCandidate toRawCandidate(PlaceCandidate cached) {
        AmapPoiCandidate raw = new AmapPoiCandidate();
        raw.setPoiId(cached.place().placeId());
        raw.setName(cached.place().name());
        raw.setAddress(cached.place().address());
        raw.setCity(cached.place().city());
        raw.setType(cached.displayType());
        raw.setTypeCode(typeCode(cached.businessType()));
        raw.setCenterPoint(toPoint(cached.place().coordinate()));
        if (!cached.navigationPoints().isEmpty()) {
            raw.setEntrancePoint(toPoint(cached.navigationPoints().getFirst()));
        }
        if (cached.navigationPoints().size() > 1) {
            raw.setExitPoint(toPoint(cached.navigationPoints().get(1)));
        }
        return raw;
    }

    private static String typeCode(BusinessType type) {
        return switch (type) {
            case TOLL_STATION -> "180200";
            case SERVICE_AREA -> "180300";
            case GAS_STATION -> "010100";
        };
    }

    private static List<GeoPoint> toGeoPoints(List<Point> points, String label) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<GeoPoint> converted = new ArrayList<>(points.size());
        for (Point point : points) {
            if (!validPoint(point)) {
                throw new AmapApiException(label + "包含无效坐标");
            }
            try {
                converted.add(toGeoPoint(point));
            } catch (IllegalArgumentException e) {
                throw new AmapApiException(label + "包含无效坐标");
            }
        }
        return List.copyOf(converted);
    }

    private static GeoPoint toGeoPoint(Point point) {
        return new GeoPoint(point.getLng(), point.getLat());
    }

    private static Point toPoint(GeoPoint point) {
        Point result = new Point();
        result.setLng(point.longitude());
        result.setLat(point.latitude());
        return result;
    }

    private static boolean validPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null
                && Double.isFinite(point.getLng()) && Double.isFinite(point.getLat());
    }

    private static boolean samePlaceName(String requested, String candidate) {
        return hasText(requested) && hasText(candidate)
                && requested.strip().equalsIgnoreCase(candidate.strip());
    }

    private static String requiredUserText(String value) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("指定途经点规划缺少原始提问");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_USER_TEXT_LENGTH) {
            throw new IllegalArgumentException("原始提问超过长度限制");
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.strip() : null;
    }

    public enum Status {
        ROUTE_READY,
        NEEDS_PLACE_CHOICE,
        PLACE_NOT_FOUND,
        PLACE_UNVERIFIABLE,
        ROUTE_NOT_FOUND,
        CONTEXT_UNAVAILABLE
    }

    public record Result(Status status,
                         long requestVersion,
                         long conditionVersion,
                         PassengerRouteSnapshot snapshot,
                         String detail,
                         String choiceTaskNo,
                         List<ChoiceSummary> choices,
                         Instant expiresAt) {
        public Result {
            Objects.requireNonNull(status, "指定途经点结果状态不能为空");
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        private static Result routeReady(long requestVersion, long conditionVersion,
                                         PassengerRouteSnapshot snapshot) {
            return new Result(Status.ROUTE_READY, requestVersion, conditionVersion,
                    snapshot, null, null, List.of(), snapshot.expiresAt());
        }

        private static Result placeChoices(long conditionVersion, String taskNo,
                                           List<ChoiceSummary> choices, Instant expiresAt) {
            return new Result(Status.NEEDS_PLACE_CHOICE, 0, conditionVersion,
                    null, null, taskNo, choices, expiresAt);
        }

        private static Result placeNotFound(long conditionVersion) {
            return simple(Status.PLACE_NOT_FOUND, conditionVersion, null);
        }

        private static Result placeUnverifiable(long conditionVersion, String detail) {
            return simple(Status.PLACE_UNVERIFIABLE, conditionVersion, detail);
        }

        private static Result routeNotFound(long conditionVersion) {
            return simple(Status.ROUTE_NOT_FOUND, conditionVersion, null);
        }

        private static Result contextUnavailable() {
            return simple(Status.CONTEXT_UNAVAILABLE, 0, null);
        }

        private static Result simple(Status status, long conditionVersion, String detail) {
            return new Result(status, 0, conditionVersion, null, detail,
                    null, List.of(), null);
        }
    }

    private record Candidate(AmapPoiCandidate raw, PlaceCandidate cached) {
    }

    private record RouteContext(long customerId,
                                String conversationNo,
                                long conditionVersion,
                                Place origin,
                                Place destination,
                                Instant expiresAt,
                                String sourceRouteRef) {
    }
}
