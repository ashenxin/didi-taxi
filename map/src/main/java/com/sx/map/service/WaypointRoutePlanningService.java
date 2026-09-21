package com.sx.map.service;

import com.sx.map.exception.AmapApiException;
import com.sx.map.exception.AmapRouteEvidenceInsufficientException;
import com.sx.map.exception.AmapRouteNotFoundException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.PassengerRouteCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 根据一组高德原始POI生成可以展示给乘客的路线候选。
 *
 * 原始 POI 用于构造高德算路请求和识别目标地点，不能直接充当经过证据。每条返回路线还须由
 * {@link WaypointRouteCandidateEvaluator} 根据导航步骤验证实际经过，才可作为候选。
 * 收费站 POI 中心坐标可在缺少入口、出口时用于请求路线，但不能单独证明经过。
 * 本服务不根据参考路线方向、合法掉头或相对耗时排除已验证路线。
 */
@Service
@Slf4j
public class WaypointRoutePlanningService {

    private static final Duration DEFAULT_CANDIDATE_TTL = Duration.ofMinutes(5);
    private static final String TOLL_STATION = "TOLL_STATION";
    private static final String SERVICE_AREA = "SERVICE_AREA";
    private static final String GAS_STATION = "GAS_STATION";

    private final AmapDrivingRouteService drivingRouteService;
    private final AmapCoordinateConvertService coordinateConvertService;
    private final WaypointRouteCandidateEvaluator candidateEvaluator;

    public WaypointRoutePlanningService(AmapDrivingRouteService drivingRouteService,
                                        AmapCoordinateConvertService coordinateConvertService,
                                        WaypointRouteCandidateEvaluator candidateEvaluator) {
        this.drivingRouteService = drivingRouteService;
        this.coordinateConvertService = coordinateConvertService;
        this.candidateEvaluator = candidateEvaluator;
    }

    /**
     * 将页面传入的 WGS84 起终点转换为高德坐标，逐条验证候选路线确实经过指定地点。
     *
     * @param origin        页面当前起点，WGS84
     * @param destination   页面当前终点，WGS84
     * @param rawCandidates 高德POI查询返回的原始候选，仅作为内部算路原料
     * @param poiType       TOLL_STATION、SERVICE_AREA或GAS_STATION
     * @return 已验证经过指定地点的路线候选，按预计行驶时间排序
     */
    public List<PassengerRouteCandidate> planCandidates(Point origin,
                                                        Point destination,
                                                        List<AmapPoiCandidate> rawCandidates,
                                                        String poiType) {
        requirePoint(origin, "起点");
        requirePoint(destination, "终点");
        PreparedCandidates prepared = prepareCandidates(rawCandidates, poiType);

        List<Point> convertedEndpoints = coordinateConvertService.convertWgs84ToAmap(List.of(origin, destination));
        if (convertedEndpoints == null || convertedEndpoints.size() != 2) {
            throw new AmapApiException("起终点坐标转换结果不完整");
        }
        return planCandidatesWithAmapEndpoints(
                convertedEndpoints.get(0), convertedEndpoints.get(1), prepared);
    }

    /**
     * 使用已经由高德地点查询确认的 GCJ02 起终点规划指定途经点路线。
     *
     * AI 客服的可信起终点保存在 map-service 内部，坐标已经是 GCJ02；该入口禁止再次执行
     * WGS84 转换。调用方仍只能传入地图服务保存的可信地点，不能采用模型或客户端提交的坐标。
     */
    public List<PassengerRouteCandidate> planCandidatesFromAmapCoordinates(
            Point amapOrigin,
            Point amapDestination,
            List<AmapPoiCandidate> rawCandidates,
            String poiType) {
        requirePoint(amapOrigin, "起点");
        requirePoint(amapDestination, "终点");
        PreparedCandidates prepared = prepareCandidates(rawCandidates, poiType);
        return planCandidatesWithAmapEndpoints(amapOrigin, amapDestination, prepared);
    }

    private static PreparedCandidates prepareCandidates(List<AmapPoiCandidate> rawCandidates,
                                                        String poiType) {
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            throw new AmapApiException("没有可用于路线规划的POI候选");
        }
        String normalizedPoiType = normalizePoiType(poiType);

        List<CandidateInput> candidateInputs = new ArrayList<>();
        for (AmapPoiCandidate candidate : rawCandidates) {
            List<Point> orderedWaypoints = orderedWaypoints(candidate, normalizedPoiType);
            if (orderedWaypoints.isEmpty()) {
                log.info("跳过缺少可用于算路的地点坐标 poiId={} poiType={}", poiId(candidate), normalizedPoiType);
                continue;
            }
            candidateInputs.add(new CandidateInput(candidate, orderedWaypoints));
        }
        if (candidateInputs.isEmpty()) {
            throw new AmapApiException("指定地点缺少可用于算路的坐标");
        }
        return new PreparedCandidates(List.copyOf(candidateInputs), normalizedPoiType);
    }

    private List<PassengerRouteCandidate> planCandidatesWithAmapEndpoints(
            Point amapOrigin,
            Point amapDestination,
            PreparedCandidates prepared) {
        List<CandidatePlan> plans = new ArrayList<>();
        boolean ambiguousPassage = false;
        boolean incompleteRouteData = false;
        boolean evaluatedAnyRoute = false;
        boolean planningFailure = false;
        boolean allPlanningFailuresRouteNotFound = true;
        String lastPlanningFailureMessage = null;
        for (CandidateInput candidateInput : prepared.candidateInputs()) {
            AmapPoiCandidate candidate = candidateInput.waypoint();
            List<Point> orderedWaypoints = candidateInput.orderedWaypoints();
            try {
                List<AmapDrivingRouteService.DrivingRouteOption> routeOptions = drivingRouteService.drivingRoutes(
                        request(amapOrigin, amapDestination, orderedWaypoints)
                );
                List<AcceptedOption> accepted = new ArrayList<>();
                for (AmapDrivingRouteService.DrivingRouteOption routeOption : routeOptions) {
                    evaluatedAnyRoute = true;
                    WaypointRouteCandidateEvaluator.Evaluation evaluation = candidateEvaluator.evaluate(
                            candidate,
                            orderedWaypoints,
                            routeOption
                    );
                    if (evaluation.status() == WaypointRouteCandidateEvaluator.Status.ACCEPTED) {
                        try {
                            accepted.add(new AcceptedOption(routeOption, evaluation,
                                    candidateNavigationSteps(routeOption.steps())));
                        } catch (AmapApiException e) {
                            incompleteRouteData = true;
                            log.warn("跳过缺少完整导航步骤的已验证路线 poiId={} reason={}",
                                    poiId(candidate), e.getMessage());
                        }
                    } else {
                        ambiguousPassage |= evaluation.status() == WaypointRouteCandidateEvaluator.Status.AMBIGUOUS;
                        log.info("路线未通过指定地点验证 poiId={} status={} reason={}",
                                poiId(candidate), evaluation.status(), evaluation.reason());
                    }
                }
                if (!accepted.isEmpty()) {
                    plans.add(new CandidatePlan(candidate, orderedWaypoints, List.copyOf(accepted)));
                }
            } catch (AmapApiException | RestClientException e) {
                // 一个原始POI失败时继续评估其他候选。只有全部失败时本方法才整体失败。
                planningFailure = true;
                allPlanningFailuresRouteNotFound &= e instanceof AmapRouteNotFoundException;
                lastPlanningFailureMessage = e instanceof AmapApiException
                        ? e.getMessage()
                        : "高德路线服务调用失败";
                log.warn("高德POI候选算路失败 poiId={} failureType={}",
                        poiId(candidate), e.getClass().getSimpleName());
            }
        }

        if (plans.isEmpty()) {
            if (ambiguousPassage) {
                throw new AmapRouteEvidenceInsufficientException(
                        "地图证据不足，暂无法确认路线是否经过指定地点");
            }
            if (incompleteRouteData) {
                throw new AmapRouteEvidenceInsufficientException(
                        "已验证路线缺少完整导航步骤，暂无法生成客服路线");
            }
            if (!evaluatedAnyRoute && lastPlanningFailureMessage != null) {
                if (planningFailure && allPlanningFailuresRouteNotFound) {
                    throw new AmapRouteNotFoundException(
                            "没有找到经过指定地点的可达路线: " + lastPlanningFailureMessage);
                }
                throw new AmapApiException("指定途经点路线规划失败: " + lastPlanningFailureMessage);
            }
            throw new AmapRouteNotFoundException("没有找到已验证经过指定地点的路线");
        }

        Instant generatedAt = Instant.now();
        Instant expiresAt = generatedAt.plus(DEFAULT_CANDIDATE_TTL);
        int resultSize = plans.stream()
                .mapToInt(plan -> plan.acceptedOptions().size())
                .sum();
        List<PassengerRouteCandidate> result = new ArrayList<>(resultSize);
        for (CandidatePlan plan : plans) {
            for (AcceptedOption accepted : plan.acceptedOptions()) {
                RouteResponse route = accepted.option().route();
                result.add(new PassengerRouteCandidate(
                        "PRC-" + UUID.randomUUID().toString().replace("-", ""),
                        plan.waypoint(),
                        plan.orderedWaypoints(),
                        passageMode(prepared.poiType()),
                        route,
                        accepted.navigationSteps(),
                        accepted.evaluation().reason().name(),
                        generatedAt,
                        expiresAt
                ));
            }
        }
        // 多个 POI 和多个 path 一起排序，避免按 POI 分组后出现慢路线排在快路线之前。
        result.sort(Comparator
                .comparingLong((PassengerRouteCandidate candidate) ->
                        valueOrMax(candidate.route().getDurationSeconds()))
                .thenComparingLong(candidate -> valueOrMax(candidate.route().getDistanceMeters()))
                .thenComparing(candidate -> poiId(candidate.waypoint())));
        return List.copyOf(result);
    }

    private static String normalizePoiType(String poiType) {
        if (poiType == null || poiType.isBlank()) {
            throw new AmapApiException("地点业务类型不能为空");
        }
        String normalized = poiType.trim().toUpperCase(Locale.ROOT);
        if (!List.of(TOLL_STATION, SERVICE_AREA, GAS_STATION).contains(normalized)) {
            throw new AmapApiException("地点业务类型仅支持TOLL_STATION、SERVICE_AREA、GAS_STATION");
        }
        return normalized;
    }

    /**
     * 服务区和加油站仍使用入口、出口有序算路。收费站无导航点时可用 POI 中心请求
     * 候选路线；中心点只是寻找路线的坐标，是否经过由导航事件另行证明。
     */
    private static List<Point> orderedWaypoints(AmapPoiCandidate candidate, String poiType) {
        if (candidate == null) {
            return List.of();
        }
        Point entrance = candidate.getEntrancePoint();
        Point exit = candidate.getExitPoint();
        if (SERVICE_AREA.equals(poiType) || GAS_STATION.equals(poiType)) {
            return isValidPoint(entrance) && isValidPoint(exit) ? List.of(entrance, exit) : List.of();
        }
        if (isValidPoint(entrance) && isValidPoint(exit)) {
            return List.of(entrance, exit);
        }
        if (isValidPoint(entrance)) {
            return List.of(entrance);
        }
        if (isValidPoint(exit)) {
            return List.of(exit);
        }
        if (isValidPoint(candidate.getCenterPoint())) {
            return List.of(candidate.getCenterPoint());
        }
        return List.of();
    }

    private static String passageMode(String poiType) {
        return TOLL_STATION.equals(poiType)
                ? PassengerRouteCandidate.PASSAGE_MODE_PASS_TOLL_CHANNEL
                : PassengerRouteCandidate.PASSAGE_MODE_ENTER_AND_EXIT;
    }

    private static RouteRequest request(Point origin, Point destination, List<Point> waypoints) {
        RouteRequest request = new RouteRequest();
        request.setOrigin(origin);
        request.setDest(destination);
        request.setWaypoints(waypoints);
        return request;
    }

    private static void requirePoint(Point point, String label) {
        if (point == null || point.getLng() == null || point.getLat() == null) {
            throw new AmapApiException(label + "缺少有效经纬度");
        }
    }

    private static boolean isValidPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null;
    }

    private static String poiId(AmapPoiCandidate candidate) {
        return candidate == null || candidate.getPoiId() == null ? "unknown" : candidate.getPoiId();
    }

    private static long valueOrMax(Long value) {
        return value == null ? Long.MAX_VALUE : value;
    }

    private static List<PassengerRouteCandidate.NavigationStep> candidateNavigationSteps(
            List<AmapDrivingRouteService.NavigationStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new AmapApiException("导航步骤为空");
        }
        List<PassengerRouteCandidate.NavigationStep> converted = new ArrayList<>(steps.size());
        for (AmapDrivingRouteService.NavigationStep step : steps) {
            if (step == null || step.polyline() == null || step.polyline().isEmpty()) {
                throw new AmapApiException("导航步骤或分段折线为空");
            }
            List<Point> polyline = new ArrayList<>(step.polyline().size());
            for (Point point : step.polyline()) {
                if (!isValidPoint(point)) {
                    throw new AmapApiException("导航步骤包含无效坐标");
                }
                Point copy = new Point();
                copy.setLng(point.getLng());
                copy.setLat(point.getLat());
                polyline.add(copy);
            }
            converted.add(new PassengerRouteCandidate.NavigationStep(
                    step.instruction(), step.action(), step.assistantAction(),
                    step.orientation(), step.roadName(), polyline));
        }
        return List.copyOf(converted);
    }

    private record AcceptedOption(AmapDrivingRouteService.DrivingRouteOption option,
                                  WaypointRouteCandidateEvaluator.Evaluation evaluation,
                                  List<PassengerRouteCandidate.NavigationStep> navigationSteps) {
    }

    private record CandidateInput(AmapPoiCandidate waypoint,
                                  List<Point> orderedWaypoints) {
    }

    private record PreparedCandidates(List<CandidateInput> candidateInputs, String poiType) {
    }

    private record CandidatePlan(AmapPoiCandidate waypoint,
                                 List<Point> orderedWaypoints,
                                 List<AcceptedOption> acceptedOptions) {
    }
}
