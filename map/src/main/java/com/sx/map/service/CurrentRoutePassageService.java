package com.sx.map.service;

import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
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

    private final WaypointRouteCandidateEvaluator evaluator;

    public CurrentRoutePassageService(WaypointRouteCandidateEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public CheckResult check(AmapDrivingRouteService.DrivingRouteOption currentRoute,
                             AmapPoiCandidate place) {
        if (currentRoute == null || currentRoute.route() == null
                || !"gaode".equals(currentRoute.route().getProvider())) {
            return new CheckResult(Verdict.UNVERIFIABLE, "REAL_ROUTE_REQUIRED",
                    "当前路线不是可核查的高德真实路线");
        }
        if (place == null) {
            return new CheckResult(Verdict.UNVERIFIABLE, "PLACE_MISSING", "指定地点尚未确认");
        }

        WaypointRouteCandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
                place, navigationPoints(place), currentRoute);
        Verdict verdict = switch (evaluation.status()) {
            case ACCEPTED -> Verdict.PASSED;
            case REJECTED -> Verdict.NOT_PASSED;
            case AMBIGUOUS -> Verdict.UNVERIFIABLE;
        };
        return new CheckResult(verdict, evaluation.reason().name(), evaluation.detail());
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
            return place.getTypeCode().startsWith("180200");
        }
        return place.getType() != null && place.getType().contains("收费站");
    }

    private static boolean validPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null;
    }

    public enum Verdict { PASSED, NOT_PASSED, UNVERIFIABLE }

    public record CheckResult(Verdict verdict, String reason, String detail) {
    }
}
