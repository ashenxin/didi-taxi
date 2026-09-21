package com.sx.map.ai.service;

import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.service.PassengerRoutePartialEndpointCache.EndpointRole;
import com.sx.map.exception.AmapRouteEvidenceInsufficientException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.AmapDrivingRouteService;
import com.sx.map.service.CurrentRouteSemanticConsistencyEvaluator;
import com.sx.map.service.WaypointRouteCandidateEvaluator;

import java.util.List;
import java.util.regex.Pattern;

/** 对收费站端点检查同一路径上的到达事件及上下高速方向，证据不足时不出路线卡。 */
final class PassengerRouteEndpointDirectionValidator {
    private static final double ENDPOINT_MAX_DISTANCE_METERS = 300D;
    private static final double TRANSITION_MAX_TRAVEL_METERS = 1_000D;
    private static final double EARTH_RADIUS_METERS = 6_371_000D;
    private static final Pattern HIGHWAY_NUMBER = Pattern.compile("(?i)(?:G|S)\\d{1,4}");
    private static final WaypointRouteCandidateEvaluator PASSAGE = new WaypointRouteCandidateEvaluator();
    private static final CurrentRouteSemanticConsistencyEvaluator DIRECTION =
            new CurrentRouteSemanticConsistencyEvaluator();

    private PassengerRouteEndpointDirectionValidator() {
    }

    static void validate(PassengerRouteSnapshot.Place origin,
                         PassengerRouteSnapshot.Place destination,
                         AmapDrivingRouteService.DrivingRouteOption option) {
        validateEndpoint(origin, EndpointRole.ORIGIN, option);
        validateEndpoint(destination, EndpointRole.DESTINATION, option);
    }

    private static void validateEndpoint(PassengerRouteSnapshot.Place place, EndpointRole role,
                                         AmapDrivingRouteService.DrivingRouteOption option) {
        if (!PassengerRouteEndpointResolver.directionCompatible(place.name(), null, role)) {
            throw new AmapRouteEvidenceInsufficientException("所选收费站出入口与起终点通行方向相反");
        }
        String name = place.name().split("[（(]", 2)[0].strip()
                .replaceFirst("(入口|出口)$", "");
        // 普通地点无收费站通行承诺；“XX出口”没有可核对的收费站身份时也不能肯定出卡。
        if (!name.contains("收费站")) {
            if (place.name().contains("高速") && (place.name().contains("入口")
                    || place.name().contains("出口"))) {
                throw new AmapRouteEvidenceInsufficientException("高速出入口缺少可核对的收费站身份");
            }
            return;
        }
        if (!name.endsWith("收费站") || name.length() <= "收费站".length()) {
            throw new AmapRouteEvidenceInsufficientException("收费站名称不足以核对导航事件");
        }
        AmapPoiCandidate station = new AmapPoiCandidate();
        station.setName(name);
        station.setTypeCode("180200");
        Point center = new Point();
        center.setLng(place.coordinate().longitude());
        center.setLat(place.coordinate().latitude());
        station.setCenterPoint(center);
        if (!endpointAnchorNear(center, role, option)) {
            throw new AmapRouteEvidenceInsufficientException("路线端点未与所选收费站位置对应");
        }
        CurrentRouteCheckMode mode = role == EndpointRole.ORIGIN
                ? CurrentRouteCheckMode.ENTER_HIGHWAY : CurrentRouteCheckMode.USE_EXIT;
        WaypointRouteCandidateEvaluator.Evaluation passage = PASSAGE.evaluate(station, List.of(), option);
        if (passage.status() == WaypointRouteCandidateEvaluator.Status.ACCEPTED
                && arrivalNearTerminal(name, center, role, option)) {
            CurrentRouteSemanticConsistencyEvaluator.Evaluation direction =
                    DIRECTION.evaluate(option, station, List.of(), mode, true);
            if (direction.status() == CurrentRouteSemanticConsistencyEvaluator.Status.VALID) {
                return;
            }
            if (direction.status() == CurrentRouteSemanticConsistencyEvaluator.Status.CONFLICT) {
                throw new AmapRouteEvidenceInsufficientException("路线在所选收费站的通行方向相反");
            }
        }
        // 高德有时将入口匝道与随后高速主线合成同一步，首段就叫“XX高速入口”，
        // 因而既没有普通路→高速的独立两步，也没有“进入高速”动作。此分支仅用于
        // 明确选中的收费站入口，且首段必须从该 POI 起步并写明由入口接入同编号高速。
        if (role == EndpointRole.ORIGIN && originRampProven(place, center, option)) {
            return;
        }
        // 起终点不是途经点：从站点出发或到站点结束时，导航可能没有“到达收费站”事件。
        // 此时只用端点附近、路线开头/结尾的有序道路变化证明方向；不能拿整条路线
        // 任意位置的高速路段，或仅凭 POI 中心点接近，冒充从该站驶入/驶出。
        if (passage.reason() == WaypointRouteCandidateEvaluator.Reason.MAINLINE_BYPASSES_TOLL_STATION
                || !endpointTransitionProven(center, role, option)) {
            throw new AmapRouteEvidenceInsufficientException("路线未证明从所选收费站按正确方向通行");
        }
    }

    private static boolean originRampProven(PassengerRouteSnapshot.Place place, Point station,
                                            AmapDrivingRouteService.DrivingRouteOption option) {
        if (!place.name().contains("入口") || option.steps().isEmpty()) return false;
        AmapDrivingRouteService.NavigationStep first = option.steps().getFirst();
        if (first == null || first.polyline().size() < 2
                || !near(station, first.polyline().getFirst())) return false;
        String ramp = first.roadName();
        if (!ramp.endsWith("入口") || !HIGHWAY_NUMBER.matcher(ramp).find()) return false;
        String instruction = first.instruction();
        String action = first.action() + first.assistantAction() + instruction;
        if (action.contains("掉头") || action.contains("折返") || action.contains("驶出高速")) return false;
        int via = instruction.indexOf("途径");
        if (!instruction.startsWith("沿" + ramp) || via < 0) return false;
        var roadNumber = HIGHWAY_NUMBER.matcher(ramp);
        return roadNumber.find() && instruction.substring(via + 2).contains(roadNumber.group());
    }

    private static boolean endpointTransitionProven(Point station, EndpointRole role,
                                                    AmapDrivingRouteService.DrivingRouteOption option) {
        if (!endpointAnchorNear(station, role, option) || option.steps().isEmpty()) {
            return false;
        }
        List<AmapDrivingRouteService.NavigationStep> steps = option.steps();
        RoadState adjacent = RoadState.UNKNOWN;
        boolean alreadyOnHighway = false;
        double traveled = 0D;
        for (int offset = 0; offset < steps.size() && traveled <= TRANSITION_MAX_TRAVEL_METERS; offset++) {
            int index = role == EndpointRole.ORIGIN ? offset : steps.size() - 1 - offset;
            AmapDrivingRouteService.NavigationStep step = steps.get(index);
            if (step == null || step.polyline().isEmpty()) return false;
            Point transition = role == EndpointRole.ORIGIN
                    ? step.polyline().getFirst() : step.polyline().getLast();
            RoadState state = roadState(step);
            boolean orderedTransition = adjacent == RoadState.ORDINARY && state == RoadState.HIGHWAY;
            // 反向遍历终点时先读到普通道路、再读到高速；对应正向的高速→普通道路。
            if (!alreadyOnHighway && near(station, transition)
                    && (orderedTransition || explicitEndpointAction(step, role))) {
                return true;
            }
            if (state == RoadState.HIGHWAY) alreadyOnHighway = true;
            adjacent = state; // 中间步骤道路未知时，不能跨过它拼接一个确定的方向变化。
            traveled += polylineLength(step.polyline());
        }
        return false;
    }

    private static boolean endpointAnchorNear(Point station, EndpointRole role,
                                              AmapDrivingRouteService.DrivingRouteOption option) {
        if (option == null || option.route() == null || option.route().getPolyline() == null
                || option.route().getPolyline().size() < 2) return false;
        List<Point> line = option.route().getPolyline();
        return near(station, role == EndpointRole.ORIGIN ? line.getFirst() : line.getLast());
    }

    private static boolean arrivalNearTerminal(String stationName, Point station, EndpointRole role,
                                               AmapDrivingRouteService.DrivingRouteOption option) {
        String base = stationName.substring(0, stationName.length() - "收费站".length());
        List<AmapDrivingRouteService.NavigationStep> steps = option.steps();
        double traveled = 0D;
        for (int offset = 0; offset < steps.size() && traveled <= TRANSITION_MAX_TRAVEL_METERS; offset++) {
            int index = role == EndpointRole.ORIGIN ? offset : steps.size() - 1 - offset;
            AmapDrivingRouteService.NavigationStep step = steps.get(index);
            if (step == null || step.polyline().isEmpty()) return false;
            String context = step.roadName() + " " + step.instruction();
            boolean matchingStation = context.contains(base + "收费站")
                    || context.contains(base + "互通") || context.contains(base + "枢纽");
            boolean arrival = step.assistantAction().contains("到达收费站")
                    || step.action().contains("到达收费站")
                    || step.instruction().contains("到达收费站");
            Point event = step.polyline().getLast();
            double eventTravel = role == EndpointRole.ORIGIN
                    ? traveled + polylineLength(step.polyline()) : traveled;
            if (matchingStation && arrival && near(station, event)
                    && eventTravel <= TRANSITION_MAX_TRAVEL_METERS) {
                return true;
            }
            traveled += polylineLength(step.polyline());
        }
        return false;
    }

    private static boolean explicitEndpointAction(AmapDrivingRouteService.NavigationStep step,
                                                   EndpointRole role) {
        String action = step.action() + " " + step.assistantAction() + " " + step.instruction();
        return role == EndpointRole.ORIGIN
                ? action.contains("进入高速") || action.contains("驶入高速")
                : action.contains("驶出高速") || action.contains("离开高速");
    }

    private static RoadState roadState(AmapDrivingRouteService.NavigationStep step) {
        String road = step.roadName();
        if (road == null || road.isBlank()) return RoadState.UNKNOWN;
        return road.contains("高速") || HIGHWAY_NUMBER.matcher(road).find()
                ? RoadState.HIGHWAY : RoadState.ORDINARY;
    }

    private static double polylineLength(List<Point> points) {
        double length = 0D;
        for (int index = 1; index < points.size(); index++) {
            length += distanceMeters(points.get(index - 1), points.get(index));
        }
        return length;
    }

    private static boolean near(Point first, Point second) {
        return first != null && second != null && first.getLng() != null && first.getLat() != null
                && second.getLng() != null && second.getLat() != null
                && Double.isFinite(first.getLng()) && Double.isFinite(first.getLat())
                && Double.isFinite(second.getLng()) && Double.isFinite(second.getLat())
                && distanceMeters(first, second) <= ENDPOINT_MAX_DISTANCE_METERS;
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

    private enum RoadState { HIGHWAY, ORDINARY, UNKNOWN }
}
