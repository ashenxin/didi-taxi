package com.sx.map.service;

import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 只核查调用方持有的那一条真实地图路线是否经过指定地点，不重新请求地图规划路线。
 *
 * 调用方必须先按乘客身份、起终点和有效期解析服务端路线快照，并确认 POI 身份。
 * 本类不接收客户端折线，也不负责路线引用的归属校验。
 */
@Service
public class CurrentRoutePassageService {

    private final CurrentRouteRelationEvaluator evaluator;

    public CurrentRoutePassageService(CurrentRouteRelationEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * 核查 AI 客服会话中当前展示的路线快照。
     *
     * 调用方必须先使用 routeRef、认证乘客、会话和条件版本从
     * {@link com.sx.map.ai.service.PassengerRouteSnapshotCache} 读取快照。本方法只把快照中
     * 已保存的折线和导航步骤转换为既有验证器需要的结构，不重新调用高德算路，也不接受
     * 客户端提交的折线、坐标或“是否经过”结论。
     */
    public CheckResult check(PassengerRouteSnapshot currentRoute, AmapPoiCandidate place) {
        return check(currentRoute, place, CurrentRouteCheckMode.VISIT_PLACE);
    }

    public CheckResult check(PassengerRouteSnapshot currentRoute, AmapPoiCandidate place,
                             CurrentRouteCheckMode mode) {
        return check(toDrivingRouteOption(currentRoute), place, mode);
    }

    public CheckResult check(AmapDrivingRouteService.DrivingRouteOption currentRoute,
                             AmapPoiCandidate place) {
        return check(currentRoute, place, CurrentRouteCheckMode.VISIT_PLACE);
    }

    public CheckResult check(AmapDrivingRouteService.DrivingRouteOption currentRoute,
                             AmapPoiCandidate place,
                             CurrentRouteCheckMode mode) {
        if (currentRoute == null || currentRoute.route() == null
                || !"gaode".equals(currentRoute.route().getProvider())) {
            return new CheckResult(Verdict.UNVERIFIABLE, "REAL_ROUTE_REQUIRED",
                    "当前路线不是可核查的高德真实路线");
        }
        if (place == null) {
            return new CheckResult(Verdict.UNVERIFIABLE, "PLACE_MISSING", "指定地点尚未确认");
        }

        CurrentRouteRelationEvaluator.Evaluation evaluation = evaluator.evaluate(
                currentRoute, place, navigationPoints(place), mode);
        Verdict verdict = switch (evaluation.status()) {
            case ACCEPTED -> Verdict.PASSED;
            case REJECTED -> Verdict.NOT_PASSED;
            case AMBIGUOUS -> Verdict.UNVERIFIABLE;
            case CONFLICT -> Verdict.CONFLICT;
        };
        return new CheckResult(verdict, evaluation.reason(), evaluation.detail());
    }

    /**
     * 未指定“驶入/驶出/进入”动作时，按实际通过、仅附近经过、不经过、无法确认
     * 给出复合关系，不再要求乘客先选择核查含义。
     */
    public RelationCheck checkRelation(PassengerRouteSnapshot currentRoute, AmapPoiCandidate place) {
        return checkRelation(toDrivingRouteOption(currentRoute), place);
    }

    public RelationCheck checkRelation(AmapDrivingRouteService.DrivingRouteOption currentRoute,
                                       AmapPoiCandidate place) {
        if (currentRoute == null || currentRoute.route() == null
                || !"gaode".equals(currentRoute.route().getProvider())) {
            return new RelationCheck(Verdict.UNVERIFIABLE, CurrentRouteCheckMode.PASS_NEARBY,
                    "REAL_ROUTE_REQUIRED", "当前路线不是可核查的高德真实路线");
        }
        if (place == null) {
            return new RelationCheck(Verdict.UNVERIFIABLE, CurrentRouteCheckMode.PASS_NEARBY,
                    "PLACE_MISSING", "指定地点尚未确认");
        }
        CurrentRouteRelationEvaluator.RelationEvaluation evaluation = evaluator.evaluateRelation(
                currentRoute, place, navigationPoints(place));
        Verdict verdict = switch (evaluation.relation()) {
            case THROUGH, NEARBY -> Verdict.PASSED;
            case NOT_ON_ROUTE -> Verdict.NOT_PASSED;
            case UNKNOWN -> Verdict.UNVERIFIABLE;
        };
        return new RelationCheck(verdict, evaluation.mode(), evaluation.reason(), evaluation.detail());
    }

    private static AmapDrivingRouteService.DrivingRouteOption toDrivingRouteOption(
            PassengerRouteSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        RouteResponse route = new RouteResponse();
        route.setProvider(snapshot.provider());
        route.setPolyline(snapshot.polyline().stream()
                .map(CurrentRoutePassageService::toPoint)
                .toList());
        route.setDistanceMeters(snapshot.distanceMeters());
        route.setDurationSeconds(snapshot.durationSeconds());
        List<AmapDrivingRouteService.NavigationStep> steps = snapshot.navigationSteps().stream()
                .map(step -> new AmapDrivingRouteService.NavigationStep(
                        step.instruction(), step.action(), step.assistantAction(),
                        step.orientation(), step.roadName(),
                        step.polyline().stream().map(CurrentRoutePassageService::toPoint).toList()))
                .toList();
        return new AmapDrivingRouteService.DrivingRouteOption(route, steps);
    }

    private static Point toPoint(PassengerRouteSnapshot.GeoPoint source) {
        Point point = new Point();
        point.setLng(source.longitude());
        point.setLat(source.latitude());
        return point;
    }

    /**
     * 站点以可通行入口为核查目标；收费站还可用中心点匹配到达事件。
     * POI 坐标本身不是经过证据，最终结论仍由同一条路线的导航步骤决定。
     */
    private static List<Point> navigationPoints(AmapPoiCandidate place) {
        if (!isTollStation(place)) {
            return validPoint(place.getEntrancePoint()) ? List.of(place.getEntrancePoint()) : List.of();
        }
        List<Point> points = new ArrayList<>(2);
        if (validPoint(place.getEntrancePoint())) {
            points.add(place.getEntrancePoint());
        }
        if (validPoint(place.getExitPoint())) {
            points.add(place.getExitPoint());
        }
        return List.copyOf(points);
    }

    private static boolean isTollStation(AmapPoiCandidate place) {
        if (place.getTypeCode() != null && !place.getTypeCode().isBlank()) {
            return place.getTypeCode().startsWith("1802");
        }
        return place.getType() != null && place.getType().contains("收费站");
    }

    private static boolean validPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null;
    }

    public enum Verdict { PASSED, NOT_PASSED, UNVERIFIABLE, CONFLICT }

    public record CheckResult(Verdict verdict, String reason, String detail) {
    }

    /** 复合关系结论：checkMode 是服务端按证据选定的展示语义，不是乘客选择。 */
    public record RelationCheck(Verdict verdict, CurrentRouteCheckMode mode,
                                String reason, String detail) {
    }
}
