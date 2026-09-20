package com.sx.map.service;

import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 核对乘客请求的通行动作是否与目标地点附近的有序路线状态一致。
 *
 * 模型只负责把原话归一为 {@link CurrentRouteCheckMode}。本类只读取地图返回的地点角色、
 * 分段折线和导航步骤，判断“已经在高速主线上，却要求从后方入口再次驶入”这类时序冲突。
 * 缺少足够路线状态时返回 UNKNOWN，不能把导航动作缺失直接解释成冲突。
 */
@Component
public class CurrentRouteSemanticConsistencyEvaluator {
    private static final double TARGET_CONTEXT_MAX_DISTANCE_METERS = 1_000D;
    private static final double SUSTAINED_HIGHWAY_DISTANCE_METERS = 1_000D;
    private static final double EARTH_RADIUS_METERS = 6_371_000D;
    private static final Pattern HIGHWAY_NUMBER = Pattern.compile("(?i)(?:G|S)\\d{1,4}");

    public Evaluation evaluate(AmapDrivingRouteService.DrivingRouteOption route,
                               AmapPoiCandidate place,
                               List<Point> navigationPoints,
                               CurrentRouteCheckMode mode,
                               boolean targetActionProven) {
        if (mode != CurrentRouteCheckMode.ENTER_HIGHWAY
                && mode != CurrentRouteCheckMode.USE_EXIT) {
            return Evaluation.valid();
        }
        TargetRole targetRole = targetRole(place);
        if (mode == CurrentRouteCheckMode.ENTER_HIGHWAY && targetRole == TargetRole.EXIT) {
            return Evaluation.conflict("TARGET_ROLE_CONFLICT",
                    "所选地点是高速出口，不能作为驶入高速的入口核查");
        }
        if (mode == CurrentRouteCheckMode.USE_EXIT && targetRole == TargetRole.ENTRANCE) {
            return Evaluation.conflict("TARGET_ROLE_CONFLICT",
                    "所选地点是高速入口，不能作为驶出高速的出口核查");
        }
        // 匹配到目标地点的正向通行动作时，以地图事件为准，不再用道路名称作反向推断。
        if (targetActionProven) {
            return Evaluation.valid();
        }
        if (mode != CurrentRouteCheckMode.ENTER_HIGHWAY || route == null || route.steps().isEmpty()) {
            return Evaluation.unknown();
        }

        List<Point> targets = targets(place, navigationPoints);
        StepPosition targetPosition = nearestStep(route.steps(), targets);
        if (targetPosition == null
                || targetPosition.distanceMeters() > TARGET_CONTEXT_MAX_DISTANCE_METERS
                || !isHighwayStep(route.steps().get(targetPosition.stepIndex()))) {
            return Evaluation.unknown();
        }
        if (!wasAlreadyOnHighway(route.steps(), targetPosition)) {
            return Evaluation.unknown();
        }

        String highwayName = highwayName(route.steps().get(targetPosition.stepIndex()));
        String entryName = previousTollName(route.steps(), targetPosition.stepIndex());
        String detail = entryName == null
                ? "路线在接近目标地点前已经行驶在" + highwayName
                + "上，因此不会再从该入口驶入高速"
                : "路线在“" + entryName + "”已经驶入高速，接近目标地点时仍行驶在"
                + highwayName + "上，因此不会再从该入口驶入高速";
        return Evaluation.conflict("ALREADY_ON_HIGHWAY_BEFORE_TARGET", detail);
    }

    private static boolean wasAlreadyOnHighway(List<AmapDrivingRouteService.NavigationStep> steps,
                                               StepPosition targetPosition) {
        int previous = targetPosition.stepIndex() - 1;
        while (previous >= 0 && steps.get(previous).polyline().size() < 2) {
            previous--;
        }
        if (previous >= 0 && isHighwayStep(steps.get(previous))) return true;
        return distanceBeforeTarget(steps.get(targetPosition.stepIndex()).polyline(),
                targetPosition.segmentIndex()) >= SUSTAINED_HIGHWAY_DISTANCE_METERS;
    }

    private static double distanceBeforeTarget(List<Point> polyline, int nearestSegmentIndex) {
        if (polyline == null || polyline.size() < 2 || nearestSegmentIndex < 0) {
            return 0D;
        }
        double distance = 0D;
        int last = Math.min(nearestSegmentIndex, polyline.size() - 2);
        for (int i = 0; i < last; i++) {
            if (validPoint(polyline.get(i)) && validPoint(polyline.get(i + 1))) {
                distance += distanceMeters(polyline.get(i), polyline.get(i + 1));
            }
        }
        return distance;
    }

    private static StepPosition nearestStep(List<AmapDrivingRouteService.NavigationStep> steps,
                                            List<Point> targets) {
        if (targets.isEmpty()) return null;
        StepPosition nearest = null;
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            List<Point> polyline = steps.get(stepIndex).polyline();
            if (polyline == null || polyline.size() < 2) continue;
            for (int segmentIndex = 0; segmentIndex < polyline.size() - 1; segmentIndex++) {
                Point start = polyline.get(segmentIndex);
                Point end = polyline.get(segmentIndex + 1);
                if (!validPoint(start) || !validPoint(end)) continue;
                for (Point target : targets) {
                    double distance = distanceToSegmentMeters(target, start, end);
                    if (nearest == null || distance < nearest.distanceMeters()) {
                        nearest = new StepPosition(stepIndex, segmentIndex, distance);
                    }
                }
            }
        }
        return nearest;
    }

    private static String previousTollName(List<AmapDrivingRouteService.NavigationStep> steps,
                                           int targetStepIndex) {
        int index = targetStepIndex - 1;
        while (index >= 0 && (isHighwayStep(steps.get(index))
                || steps.get(index).polyline().size() < 2)) index--;
        if (index >= 0 && containsTollArrival(steps.get(index))
                && !steps.get(index).roadName().isBlank()) {
            return steps.get(index).roadName().strip();
        }
        return null;
    }

    private static boolean containsTollArrival(AmapDrivingRouteService.NavigationStep step) {
        return step.assistantAction().contains("到达收费站")
                || step.action().contains("到达收费站")
                || step.instruction().contains("到达收费站");
    }

    private static boolean isHighwayStep(AmapDrivingRouteService.NavigationStep step) {
        String context = step.roadName() + " " + step.instruction();
        return context.contains("高速") || HIGHWAY_NUMBER.matcher(context).find();
    }

    private static String highwayName(AmapDrivingRouteService.NavigationStep step) {
        return step.roadName().isBlank() ? "高速主线" : "“" + step.roadName().strip() + "”";
    }

    private static TargetRole targetRole(AmapPoiCandidate place) {
        if (place == null || place.getName() == null) return TargetRole.UNSPECIFIED;
        boolean entrance = place.getName().contains("入口");
        boolean exit = place.getName().contains("出口");
        if (entrance == exit) return TargetRole.UNSPECIFIED;
        return entrance ? TargetRole.ENTRANCE : TargetRole.EXIT;
    }

    private static List<Point> targets(AmapPoiCandidate place, List<Point> navigationPoints) {
        List<Point> result = new ArrayList<>();
        if (place != null && validPoint(place.getCenterPoint())) result.add(place.getCenterPoint());
        if (navigationPoints != null) {
            navigationPoints.stream()
                    .filter(CurrentRouteSemanticConsistencyEvaluator::validPoint)
                    .forEach(result::add);
        }
        return result;
    }

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

    private static double distanceMeters(Point first, Point second) {
        double lat1 = Math.toRadians(first.getLat());
        double lat2 = Math.toRadians(second.getLat());
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(second.getLng() - first.getLng());
        double a = Math.sin(deltaLat / 2D) * Math.sin(deltaLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLng / 2D) * Math.sin(deltaLng / 2D);
        return 2D * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
    }

    private static boolean validPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null
                && Double.isFinite(point.getLng()) && Double.isFinite(point.getLat());
    }

    private enum TargetRole { ENTRANCE, EXIT, UNSPECIFIED }

    private record StepPosition(int stepIndex, int segmentIndex, double distanceMeters) {
    }

    public enum Status { VALID, CONFLICT, UNKNOWN }

    public record Evaluation(Status status, String reason, String detail) {
        private static Evaluation valid() {
            return new Evaluation(Status.VALID, "SEMANTICALLY_VALID", null);
        }

        private static Evaluation conflict(String reason, String detail) {
            return new Evaluation(Status.CONFLICT, reason, detail);
        }

        private static Evaluation unknown() {
            return new Evaluation(Status.UNKNOWN, "ROUTE_STATE_UNKNOWN", null);
        }
    }
}
