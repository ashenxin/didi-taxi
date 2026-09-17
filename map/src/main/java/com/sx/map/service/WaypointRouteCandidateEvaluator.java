package com.sx.map.service;

import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 只根据本条地图路径的导航事实，验证路线是否到达指定地点。
 *
 * 收费站附近的高速主线可能离 POI 中心只有几十米。必须找到目标站附近的
 * “到达收费站”导航事件，不能只凭整条折线与 POI 的距离宣布通过。
 * 参考路线方向、合法掉头及绕行距离不参与本条路线的验证。
 */
@Component
public class WaypointRouteCandidateEvaluator {

    /** 首批实测收费站的事件位置距 POI 为十几米；扩大样本后还需校准此窗口。 */
    static final double ARRIVAL_EVENT_MAX_DISTANCE_METERS = 100D;
    /** 仅核对导航事件来自同一条返回路线。 */
    private static final double EVENT_ON_ROUTE_MAX_DISTANCE_METERS = 30D;
    /** 明显远离时才判定未经过；路线很近却缺少事件时返回无法确认。 */
    private static final double FAR_FROM_PLACE_METERS = 1_000D;
    private static final double EARTH_RADIUS_METERS = 6_371_000D;

    /**
     * 本类不判断车辆是否实际出行，也不替上层解决同名地点或明确的上下高速要求。
     */
    public Evaluation evaluate(AmapPoiCandidate waypoint,
                               List<Point> orderedWaypoints,
                               AmapDrivingRouteService.DrivingRouteOption option) {
        if (waypoint == null) {
            return Evaluation.rejected(Reason.POI_MISSING, "指定地点不存在");
        }
        if (option == null || option.route() == null) {
            return Evaluation.rejected(Reason.ROUTE_MISSING, "地图没有返回路线");
        }
        List<Point> routePolyline = option.route().getPolyline();
        if (routePolyline == null || routePolyline.size() < 2
                || routePolyline.stream().anyMatch(point -> !isValidPoint(point))) {
            return Evaluation.ambiguous(Reason.ROUTE_GEOMETRY_MISSING, "路线缺少完整折线");
        }
        if (option.steps().isEmpty()) {
            return Evaluation.ambiguous(Reason.NAVIGATION_STEPS_MISSING, "路线缺少导航步骤");
        }
        if (isTollStation(waypoint)) {
            return evaluateTollStation(waypoint, orderedWaypoints, option.steps(), routePolyline);
        }
        return evaluateNavigableSite(orderedWaypoints, option.steps(), routePolyline);
    }

    private static Evaluation evaluateTollStation(AmapPoiCandidate station,
                                                  List<Point> navigationPoints,
                                                  List<AmapDrivingRouteService.NavigationStep> steps,
                                                  List<Point> routePolyline) {
        List<Point> targets = new ArrayList<>();
        if (isValidPoint(station.getCenterPoint())) {
            targets.add(station.getCenterPoint());
        }
        if (navigationPoints != null) {
            navigationPoints.stream().filter(WaypointRouteCandidateEvaluator::isValidPoint).forEach(targets::add);
        }
        if (targets.isEmpty()) {
            return Evaluation.ambiguous(Reason.NAVIGATION_POINT_MISSING, "收费站缺少可核对的位置");
        }
        String stationName = stationName(station.getName());
        if (stationName.length() < 2) {
            return Evaluation.ambiguous(Reason.PLACE_IDENTITY_UNCLEAR, "无法辨认收费站所属互通");
        }

        for (AmapDrivingRouteService.NavigationStep step : steps) {
            if (!isArrival(step, "到达收费站")) {
                continue;
            }
            Point arrival = lastPoint(step.polyline());
            if (!isValidPoint(arrival)
                    || distanceToRoute(routePolyline, arrival) > EVENT_ON_ROUTE_MAX_DISTANCE_METERS
                    || distanceToAny(arrival, targets) > ARRIVAL_EVENT_MAX_DISTANCE_METERS) {
                continue;
            }
            // 路线上其他收费站也会产生相同动作；互通名称必须能对应目标站。
            String roadContext = step.roadName() + " " + step.instruction();
            if (matchesTollName(roadContext, stationName)) {
                return Evaluation.accepted(Reason.TOLL_ARRIVAL_MATCHED,
                        "目标收费站附近有对应互通的到达收费站事件");
            }
        }
        if (staysOnMainlineAtStation(steps, targets, stationName)) {
            return Evaluation.rejected(Reason.MAINLINE_BYPASSES_TOLL_STATION,
                    "路线从目标站附近的高速主线驶过，收费站到达事件发生在别处");
        }
        return unresolvedOrMissed(routePolyline, targets, Reason.TOLL_ARRIVAL_NOT_PROVEN,
                "路线靠近收费站，但没有可与目标身份匹配的到达事件");
    }

    /**
     * 只有完整的分段路线明确在目标附近保持高速主线，才将近距离经过判为“不经过收费站”。
     * 导航步骤缺失、附近进入匝道或出现目标互通但缺少事件时，仍返回无法确认。
     */
    private static boolean staysOnMainlineAtStation(List<AmapDrivingRouteService.NavigationStep> steps,
                                                    List<Point> targets, String stationName) {
        if (steps.stream().anyMatch(step -> step.polyline().size() < 2)) {
            return false;
        }
        boolean reachedAnotherToll = steps.stream()
                .filter(step -> isArrival(step, "到达收费站"))
                .map(step -> lastPoint(step.polyline()))
                .filter(WaypointRouteCandidateEvaluator::isValidPoint)
                .anyMatch(point -> distanceToAny(point, targets) > FAR_FROM_PLACE_METERS);
        if (!reachedAnotherToll) {
            return false;
        }
        boolean foundMainline = false;
        for (AmapDrivingRouteService.NavigationStep step : steps) {
            if (targets.stream().noneMatch(point -> distanceToRoute(step.polyline(), point)
                    <= ARRIVAL_EVENT_MAX_DISTANCE_METERS)) {
                continue;
            }
            String context = step.roadName() + " " + step.instruction();
            Point end = lastPoint(step.polyline());
            if (isArrival(step, "到达收费站") || isArrival(step, "到达途经地")
                    || matchesTollName(context, stationName)
                    || !isValidPoint(end)
                    || distanceToAny(end, targets) <= ARRIVAL_EVENT_MAX_DISTANCE_METERS
                    || !(context.contains("高速") || context.matches(".*G\\d+.*"))) {
                return false;
            }
            foundMainline = true;
        }
        return foundMainline;
    }

    private static Evaluation evaluateNavigableSite(List<Point> navigationPoints,
                                                    List<AmapDrivingRouteService.NavigationStep> steps,
                                                    List<Point> routePolyline) {
        if (navigationPoints == null || navigationPoints.isEmpty()
                || navigationPoints.stream().anyMatch(point -> !isValidPoint(point))) {
            return Evaluation.ambiguous(Reason.NAVIGATION_POINT_MISSING, "站点缺少可信导航点");
        }
        Point entrance = navigationPoints.getFirst();
        for (AmapDrivingRouteService.NavigationStep step : steps) {
            if (!isArrival(step, "到达途经地") && !isArrival(step, "到达服务区")) {
                continue;
            }
            Point arrival = lastPoint(step.polyline());
            if (!isValidPoint(arrival)
                    || distanceToRoute(routePolyline, arrival) > EVENT_ON_ROUTE_MAX_DISTANCE_METERS
                    || distanceMeters(arrival, entrance) > ARRIVAL_EVENT_MAX_DISTANCE_METERS) {
                continue;
            }
            if (navigationPoints.size() > 1 && !passesRemainingPointsInOrder(
                    routePolyline, arrival, navigationPoints.subList(1, navigationPoints.size()))) {
                return Evaluation.ambiguous(Reason.NAVIGATION_POINT_ORDER_UNPROVEN,
                        "已到达入口，但无法验证后续导航点顺序");
            }
            return Evaluation.accepted(Reason.SITE_ARRIVAL_MATCHED, "路线到达站点可信导航点");
        }
        return unresolvedOrMissed(routePolyline, navigationPoints, Reason.SITE_ARRIVAL_NOT_PROVEN,
                "路线靠近站点，但缺少该导航点的到达事件");
    }

    private static Evaluation unresolvedOrMissed(List<Point> routePolyline, List<Point> targets,
                                                 Reason ambiguousReason, String ambiguousDetail) {
        if (targets.stream().allMatch(point -> distanceToRoute(routePolyline, point) > FAR_FROM_PLACE_METERS)) {
            return Evaluation.rejected(Reason.ROUTE_MISSES_PLACE, "路线明显远离指定地点");
        }
        return Evaluation.ambiguous(ambiguousReason, ambiguousDetail);
    }

    private static boolean passesRemainingPointsInOrder(List<Point> routePolyline, Point arrival,
                                                        List<Point> remaining) {
        int previousIndex = nearestPointIndex(routePolyline, arrival, EVENT_ON_ROUTE_MAX_DISTANCE_METERS);
        if (previousIndex < 0) {
            return false;
        }
        for (Point point : remaining) {
            int index = nearestPointIndex(routePolyline, point, ARRIVAL_EVENT_MAX_DISTANCE_METERS);
            if (index <= previousIndex) {
                return false;
            }
            previousIndex = index;
        }
        return true;
    }

    private static int nearestPointIndex(List<Point> polyline, Point target, double maximumMeters) {
        int nearest = -1;
        double distance = maximumMeters;
        for (int i = 0; i < polyline.size(); i++) {
            double current = distanceMeters(polyline.get(i), target);
            if (current <= distance) {
                distance = current;
                nearest = i;
            }
        }
        return nearest;
    }

    private static boolean isTollStation(AmapPoiCandidate candidate) {
        if (candidate.getTypeCode() != null && !candidate.getTypeCode().isBlank()) {
            return candidate.getTypeCode().startsWith("180200");
        }
        if (candidate.getType() != null && !candidate.getType().isBlank()) {
            return candidate.getType().contains("收费站");
        }
        return candidate.getName() != null && candidate.getName().contains("收费站");
    }

    private static String stationName(String name) {
        if (name == null) {
            return "";
        }
        String base = name.split("[（(]", 2)[0].trim();
        return base.endsWith("收费站") ? base.substring(0, base.length() - 3).trim() : "";
    }

    private static boolean matchesTollName(String roadContext, String stationName) {
        return roadContext.contains(stationName + "互通")
                || roadContext.contains(stationName + "枢纽")
                || roadContext.contains(stationName + "收费站");
    }

    private static boolean isArrival(AmapDrivingRouteService.NavigationStep step, String action) {
        return step.assistantAction().contains(action) || step.instruction().contains(action);
    }

    private static Point lastPoint(List<Point> polyline) {
        return polyline == null || polyline.isEmpty() ? null : polyline.getLast();
    }

    private static double distanceToAny(Point point, List<Point> targets) {
        return targets.stream().mapToDouble(target -> distanceMeters(point, target))
                .min().orElse(Double.MAX_VALUE);
    }

    private static double distanceToRoute(List<Point> polyline, Point target) {
        double minimum = Double.MAX_VALUE;
        for (int i = 0; i < polyline.size() - 1; i++) {
            minimum = Math.min(minimum, pointToSegmentDistanceMeters(target, polyline.get(i), polyline.get(i + 1)));
        }
        return minimum;
    }

    private static double pointToSegmentDistanceMeters(Point target, Point start, Point end) {
        double latitudeRadians = Math.toRadians(target.getLat());
        double startX = Math.toRadians(start.getLng() - target.getLng()) * Math.cos(latitudeRadians) * EARTH_RADIUS_METERS;
        double startY = Math.toRadians(start.getLat() - target.getLat()) * EARTH_RADIUS_METERS;
        double endX = Math.toRadians(end.getLng() - target.getLng()) * Math.cos(latitudeRadians) * EARTH_RADIUS_METERS;
        double endY = Math.toRadians(end.getLat() - target.getLat()) * EARTH_RADIUS_METERS;
        double dx = endX - startX;
        double dy = endY - startY;
        double squared = dx * dx + dy * dy;
        double projection = squared == 0D ? 0D : Math.max(0D, Math.min(1D, -(startX * dx + startY * dy) / squared));
        return Math.hypot(startX + projection * dx, startY + projection * dy);
    }

    private static double distanceMeters(Point first, Point second) {
        if (!isValidPoint(first) || !isValidPoint(second)) {
            return Double.MAX_VALUE;
        }
        double firstLat = Math.toRadians(first.getLat());
        double secondLat = Math.toRadians(second.getLat());
        double deltaLat = secondLat - firstLat;
        double deltaLng = Math.toRadians(second.getLng() - first.getLng());
        double value = Math.sin(deltaLat / 2D) * Math.sin(deltaLat / 2D)
                + Math.cos(firstLat) * Math.cos(secondLat)
                * Math.sin(deltaLng / 2D) * Math.sin(deltaLng / 2D);
        return 2D * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(value));
    }

    private static boolean isValidPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null;
    }

    public enum Status { ACCEPTED, REJECTED, AMBIGUOUS }

    public enum Reason {
        POI_MISSING, NAVIGATION_POINT_MISSING, ROUTE_MISSING, ROUTE_GEOMETRY_MISSING,
        NAVIGATION_STEPS_MISSING, PLACE_IDENTITY_UNCLEAR, TOLL_ARRIVAL_MATCHED,
        TOLL_ARRIVAL_NOT_PROVEN, SITE_ARRIVAL_MATCHED, SITE_ARRIVAL_NOT_PROVEN,
        NAVIGATION_POINT_ORDER_UNPROVEN, ROUTE_MISSES_PLACE, MAINLINE_BYPASSES_TOLL_STATION,
        // 旧规划服务测试仍引用以下值；改造规划服务时一并删除旧方向契约。
        ROUTE_MISSES_NAVIGATION_POINT, NAVIGATION_POINT_ORDER_REVERSED, U_TURN_NEAR_WAYPOINT,
        DIRECTION_VECTOR_TOO_SHORT, OPPOSITE_DIRECTION, DIRECTION_AMBIGUOUS,
        REFERENCE_DIRECTION_MATCHED, ROUTABLE_NAVIGATION_POINT, REFERENCE_ROUTE_UNAVAILABLE,
        OUTSIDE_REFERENCE_CORRIDOR
    }

    /** 地图验证结论，不由模型自行从路线文字推断。 */
    public record Evaluation(Status status, Reason reason, String detail) {
        static Evaluation accepted(Reason reason, String detail) {
            return new Evaluation(Status.ACCEPTED, reason, detail);
        }
        static Evaluation rejected(Reason reason, String detail) {
            return new Evaluation(Status.REJECTED, reason, detail);
        }
        static Evaluation ambiguous(Reason reason, String detail) {
            return new Evaluation(Status.AMBIGUOUS, reason, detail);
        }
        /** 兼容现有规划服务排序，后续改为按已验证路线的预计时间排序。 */
        public int confidenceRank() {
            return reason == Reason.TOLL_ARRIVAL_MATCHED || reason == Reason.SITE_ARRIVAL_MATCHED
                    || reason == Reason.REFERENCE_DIRECTION_MATCHED ? 0 : 1;
        }
    }
}
