package com.sx.map.service;

import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按乘客已经确认的语义核查当前路线与地点的关系。
 *
 * “经过附近”是空间关系，可以使用路线折线核查；“下高速、上高速、进入站点”是通行动作，
 * 只能使用匹配地点身份的正向导航事件。导航事件缺失只表示证据不足，不能反推未发生动作。
 */
@Component
public class CurrentRouteRelationEvaluator {
    private static final double NEARBY_MAX_DISTANCE_METERS = 1_000D;
    private static final double EARTH_RADIUS_METERS = 6_371_000D;

    private final WaypointRouteCandidateEvaluator arrivalEvaluator;
    private final CurrentRouteSemanticConsistencyEvaluator semanticConsistencyEvaluator;

    public CurrentRouteRelationEvaluator(WaypointRouteCandidateEvaluator arrivalEvaluator,
                                         CurrentRouteSemanticConsistencyEvaluator semanticConsistencyEvaluator) {
        this.arrivalEvaluator = arrivalEvaluator;
        this.semanticConsistencyEvaluator = semanticConsistencyEvaluator;
    }

    public Evaluation evaluate(AmapDrivingRouteService.DrivingRouteOption route,
                               AmapPoiCandidate place,
                               List<Point> navigationPoints,
                               CurrentRouteCheckMode mode) {
        if (mode == null || mode == CurrentRouteCheckMode.UNSPECIFIED) {
            return Evaluation.ambiguous("CHECK_MODE_UNSPECIFIED", "尚未确认乘客要核查的经过含义");
        }
        if (route == null || route.route() == null || route.route().getPolyline() == null
                || route.route().getPolyline().size() < 2) {
            return Evaluation.ambiguous("ROUTE_GEOMETRY_MISSING", "当前路线缺少完整折线");
        }
        if (mode == CurrentRouteCheckMode.PASS_NEARBY) {
            return evaluateNearby(route.route().getPolyline(), place, navigationPoints);
        }
        return evaluateAction(route, place, navigationPoints, mode);
    }

    private Evaluation evaluateNearby(List<Point> routePolyline,
                                      AmapPoiCandidate place,
                                      List<Point> navigationPoints) {
        List<Point> targets = targets(place, navigationPoints);
        if (targets.isEmpty()) {
            return Evaluation.ambiguous("PLACE_LOCATION_MISSING", "地图地点缺少可核查位置");
        }
        double distance = targets.stream()
                .mapToDouble(target -> distanceToRoute(routePolyline, target))
                .min().orElse(Double.POSITIVE_INFINITY);
        long rounded = Math.round(distance);
        if (distance <= NEARBY_MAX_DISTANCE_METERS) {
            return Evaluation.accepted("ROUTE_PASSES_NEARBY",
                    "路线折线与目标地点最近约" + rounded + "米");
        }
        return Evaluation.rejected("ROUTE_NOT_NEARBY",
                "路线折线与目标地点最近约" + rounded + "米，未进入1公里附近范围");
    }

    private Evaluation evaluateAction(AmapDrivingRouteService.DrivingRouteOption route,
                                      AmapPoiCandidate place,
                                      List<Point> navigationPoints,
                                      CurrentRouteCheckMode mode) {
        if ((mode == CurrentRouteCheckMode.USE_EXIT || mode == CurrentRouteCheckMode.ENTER_HIGHWAY)
                && !isTollStation(place)) {
            return Evaluation.ambiguous("HIGHWAY_ACCESS_REQUIRES_TOLL_STATION",
                    "所选地点不是可核查的高速收费站");
        }
        WaypointRouteCandidateEvaluator.Evaluation arrival = arrivalEvaluator.evaluate(
                place, navigationPoints, route);
        CurrentRouteSemanticConsistencyEvaluator.Evaluation consistency =
                semanticConsistencyEvaluator.evaluate(route, place, navigationPoints, mode,
                        arrival.status() == WaypointRouteCandidateEvaluator.Status.ACCEPTED);
        if (consistency.status() == CurrentRouteSemanticConsistencyEvaluator.Status.CONFLICT) {
            return Evaluation.conflict(consistency.reason(), consistency.detail());
        }
        if (arrival.status() == WaypointRouteCandidateEvaluator.Status.ACCEPTED) {
            if ((mode == CurrentRouteCheckMode.USE_EXIT || mode == CurrentRouteCheckMode.ENTER_HIGHWAY)
                    && consistency.status() == CurrentRouteSemanticConsistencyEvaluator.Status.UNKNOWN) {
                return Evaluation.ambiguous(consistency.reason(), consistency.detail());
            }
            return acceptedAction(place, mode, arrival.detail());
        }
        if (arrival.reason() == WaypointRouteCandidateEvaluator.Reason.ROUTE_MISSES_PLACE) {
            return Evaluation.rejected("ROUTE_MISSES_PLACE", arrival.detail());
        }
        String action = switch (mode) {
            case USE_EXIT -> "从该出口驶出高速";
            case ENTER_HIGHWAY -> "从该入口驶入高速";
            case VISIT_PLACE -> "实际进入或通过该地点";
            default -> "完成指定通行动作";
        };
        return Evaluation.ambiguous("ACTION_NOT_PROVEN",
                "当前地图导航详情没有提供“" + action + "”的明确证据");
    }

    private static Evaluation acceptedAction(AmapPoiCandidate place,
                                             CurrentRouteCheckMode mode,
                                             String arrivalDetail) {
        String canonicalName = place.getName() == null ? "" : place.getName();
        return switch (mode) {
            case VISIT_PLACE -> Evaluation.accepted("PLACE_ARRIVAL_MATCHED", arrivalDetail);
            case USE_EXIT -> canonicalName.contains("出口")
                    ? Evaluation.accepted("EXIT_ARRIVAL_MATCHED",
                    "目标收费站前后道路状态与从该出口驶出高速一致")
                    : Evaluation.ambiguous("EXIT_ROLE_NOT_PROVEN",
                    "路线有匹配的收费站到达事件，但地图没有标明这是出口");
            case ENTER_HIGHWAY -> canonicalName.contains("入口")
                    ? Evaluation.accepted("ENTRANCE_ARRIVAL_MATCHED",
                    "目标收费站前后道路状态与从该入口驶入高速一致")
                    : Evaluation.ambiguous("ENTRANCE_ROLE_NOT_PROVEN",
                    "路线有匹配的收费站到达事件，但地图没有标明这是入口");
            default -> Evaluation.ambiguous("CHECK_MODE_UNSUPPORTED", "当前核查含义不支持该通行动作");
        };
    }

    private static List<Point> targets(AmapPoiCandidate place, List<Point> navigationPoints) {
        List<Point> result = new ArrayList<>();
        if (place != null && validPoint(place.getCenterPoint())) result.add(place.getCenterPoint());
        if (navigationPoints != null) {
            navigationPoints.stream().filter(CurrentRouteRelationEvaluator::validPoint).forEach(result::add);
        }
        return result;
    }

    private static boolean isTollStation(AmapPoiCandidate place) {
        if (place == null) return false;
        if (place.getTypeCode() != null && !place.getTypeCode().isBlank()) {
            return place.getTypeCode().startsWith("1802");
        }
        return place.getType() != null && place.getType().contains("收费站");
    }

    private static double distanceToRoute(List<Point> polyline, Point target) {
        double minimum = Double.POSITIVE_INFINITY;
        for (int i = 1; i < polyline.size(); i++) {
            Point start = polyline.get(i - 1);
            Point end = polyline.get(i);
            if (!validPoint(start) || !validPoint(end)) continue;
            minimum = Math.min(minimum, distanceToSegmentMeters(target, start, end));
        }
        return minimum;
    }

    /** 在目标纬度附近投影到局部平面，避免只比较折线顶点造成长线段漏判。 */
    private static double distanceToSegmentMeters(Point target, Point start, Point end) {
        double latitude = Math.toRadians(target.getLat());
        double x1 = Math.toRadians(start.getLng() - target.getLng()) * Math.cos(latitude)
                * EARTH_RADIUS_METERS;
        double y1 = Math.toRadians(start.getLat() - target.getLat()) * EARTH_RADIUS_METERS;
        double x2 = Math.toRadians(end.getLng() - target.getLng()) * Math.cos(latitude)
                * EARTH_RADIUS_METERS;
        double y2 = Math.toRadians(end.getLat() - target.getLat()) * EARTH_RADIUS_METERS;
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        double ratio = lengthSquared == 0D ? 0D : -(x1 * dx + y1 * dy) / lengthSquared;
        ratio = Math.max(0D, Math.min(1D, ratio));
        return Math.hypot(x1 + ratio * dx, y1 + ratio * dy);
    }

    private static boolean validPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null
                && Double.isFinite(point.getLng()) && Double.isFinite(point.getLat());
    }

    public enum Status { ACCEPTED, REJECTED, AMBIGUOUS, CONFLICT }

    public record Evaluation(Status status, String reason, String detail) {
        private static Evaluation accepted(String reason, String detail) {
            return new Evaluation(Status.ACCEPTED, reason, detail);
        }

        private static Evaluation rejected(String reason, String detail) {
            return new Evaluation(Status.REJECTED, reason, detail);
        }

        private static Evaluation ambiguous(String reason, String detail) {
            return new Evaluation(Status.AMBIGUOUS, reason, detail);
        }

        private static Evaluation conflict(String reason, String detail) {
            return new Evaluation(Status.CONFLICT, reason, detail);
        }
    }
}
