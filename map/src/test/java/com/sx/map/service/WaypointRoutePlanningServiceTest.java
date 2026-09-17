package com.sx.map.service;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.PassengerRouteCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaypointRoutePlanningServiceTest {

    private AmapDrivingRouteService drivingRouteService;
    private AmapCoordinateConvertService coordinateConvertService;
    private WaypointRouteCandidateEvaluator candidateEvaluator;
    private WaypointRoutePlanningService service;

    @BeforeEach
    void setUp() {
        drivingRouteService = mock(AmapDrivingRouteService.class);
        coordinateConvertService = mock(AmapCoordinateConvertService.class);
        candidateEvaluator = mock(WaypointRouteCandidateEvaluator.class);
        when(coordinateConvertService.convertWgs84ToAmap(anyList()))
                .thenAnswer(invocation -> invocation.<List<Point>>getArgument(0));
        service = new WaypointRoutePlanningService(drivingRouteService, coordinateConvertService, candidateEvaluator);
    }

    @Test
    void centerOnlyWuchangPoiCanFindRouteButNeedsTollArrivalProof() {
        service = new WaypointRoutePlanningService(
                drivingRouteService, coordinateConvertService, new WaypointRouteCandidateEvaluator());
        Point center = point(120.046785, 30.279236);
        AmapPoiCandidate wuchang = toll("B0WUCHANG", "五常收费站(G25长深高速出口千岛湖方向)", center);
        Point arrival = point(120.046877, 30.279328);
        var tollStep = step("五常互通", "沿五常互通减速行驶到达收费站", "到达收费站",
                point(120.0468, 30.2800), arrival);
        var onward = step("五常街道", "继续行驶", "", arrival, point(120.061, 30.254));
        when(drivingRouteService.drivingRoutes(any()))
                .thenReturn(List.of(optionWithSteps(48_000L, 2_900L, tollStep, onward)));

        List<PassengerRouteCandidate> result = service.planCandidates(
                point(120.020, 30.543), point(120.061, 30.254), List.of(wuchang), "TOLL_STATION");

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.waypoint()).isSameAs(wuchang);
            assertThat(candidate.routingWaypoints()).containsExactly(center);
            assertThat(candidate.passageMode()).isEqualTo(PassengerRouteCandidate.PASSAGE_MODE_PASS_TOLL_CHANNEL);
            assertThat(candidate.passageVerification()).isEqualTo("TOLL_ARRIVAL_MATCHED");
            assertThat(candidate.route().getDurationSeconds()).isEqualTo(2_900L);
        });
        ArgumentCaptor<RouteRequest> requestCaptor = ArgumentCaptor.forClass(RouteRequest.class);
        verify(drivingRouteService).drivingRoutes(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getWaypoints()).containsExactly(center);
        verify(drivingRouteService, never()).drivingRoute(any());
    }

    @Test
    void mainlineNearWuchangDoesNotTurnCenterWaypointIntoPassageProof() {
        service = new WaypointRoutePlanningService(
                drivingRouteService, coordinateConvertService, new WaypointRouteCandidateEvaluator());
        AmapPoiCandidate wuchang = toll("B0WUCHANG", "五常收费站", point(120.046785, 30.279236));
        Point nearMainline = point(120.046785, 30.279506);
        var mainline = step("G25长深高速", "沿G25长深高速继续行驶", "进入匝道",
                point(120.020, 30.543), nearMainline, point(120.048, 30.241));
        var liuxiaToll = step("留下枢纽", "沿留下枢纽到达收费站", "到达收费站",
                point(120.048, 30.241), point(120.048964, 30.239406));
        when(drivingRouteService.drivingRoutes(any()))
                .thenReturn(List.of(optionWithSteps(45_000L, 2_700L, mainline, liuxiaToll)));

        assertThatThrownBy(() -> service.planCandidates(
                point(120.020, 30.543), point(120.061, 30.254), List.of(wuchang), "TOLL_STATION"))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("没有找到已验证经过指定地点的路线");
        verify(drivingRouteService).drivingRoutes(any());
    }

    @Test
    void keepsOnlyVerifiedRoutesAndSortsAcrossPoisAndPaths() {
        AmapPoiCandidate first = waypoint("B0FIRST", "下沙服务区一号站",
                point(120.29, 30.30), point(120.30, 30.30));
        AmapPoiCandidate second = waypoint("B0SECOND", "下沙服务区二号站",
                point(120.31, 30.30), point(120.32, 30.30));
        AmapPoiCandidate missed = waypoint("B0MISSED", "下沙服务区三号站",
                point(120.33, 30.30), point(120.34, 30.30));
        when(drivingRouteService.drivingRoutes(any()))
                .thenReturn(List.of(option(route(52_000L, 5_000L)), option(route(50_000L, 3_200L))))
                .thenReturn(List.of(option(route(51_000L, 4_000L))))
                .thenReturn(List.of(option(route(46_000L, 2_000L))));
        when(candidateEvaluator.evaluate(any(), anyList(), any()))
                .thenAnswer(invocation -> invocation.<AmapPoiCandidate>getArgument(0) == missed
                        ? WaypointRouteCandidateEvaluator.Evaluation.rejected(
                                WaypointRouteCandidateEvaluator.Reason.ROUTE_MISSES_PLACE, "未经过")
                        : WaypointRouteCandidateEvaluator.Evaluation.accepted(
                                WaypointRouteCandidateEvaluator.Reason.SITE_ARRIVAL_MATCHED, "已到达站点"));

        List<PassengerRouteCandidate> result = service.planCandidates(
                point(120.10, 30.20), point(120.50, 30.40),
                List.of(first, second, missed), "SERVICE_AREA");

        assertThat(result).extracting(candidate -> candidate.route().getDurationSeconds())
                .containsExactly(3_200L, 4_000L, 5_000L);
        assertThat(result).extracting(PassengerRouteCandidate::waypoint)
                .containsExactly(first, second, first);
        assertThat(result).extracting(PassengerRouteCandidate::routeCandidateId).doesNotHaveDuplicates();
        assertThat(result).allSatisfy(candidate ->
                assertThat(candidate.passageVerification()).isEqualTo("SITE_ARRIVAL_MATCHED"));
        ArgumentCaptor<RouteRequest> requestCaptor = ArgumentCaptor.forClass(RouteRequest.class);
        verify(drivingRouteService, times(3)).drivingRoutes(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().getFirst().getWaypoints())
                .containsExactly(first.getEntrancePoint(), first.getExitPoint());
        assertThat(requestCaptor.getAllValues().get(1).getWaypoints())
                .containsExactly(second.getEntrancePoint(), second.getExitPoint());
        verify(drivingRouteService, never()).drivingRoute(any());
    }

    @Test
    void serviceAreaStillRequiresEntranceAndExit() {
        AmapPoiCandidate incomplete = waypoint("B0INCOMPLETE", "下沙服务区",
                point(120.29, 30.30), null);
        incomplete.setCenterPoint(point(120.295, 30.30));

        assertThatThrownBy(() -> service.planCandidates(
                point(120.10, 30.20), point(120.50, 30.40), List.of(incomplete), "SERVICE_AREA"))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("缺少可用于算路的坐标");
        verify(coordinateConvertService, never()).convertWgs84ToAmap(anyList());
        verify(drivingRouteService, never()).drivingRoutes(any());
    }

    @Test
    void tollStationKeepsAvailableNavigationPointWhenCenterAlsoExists() {
        Point entrance = point(120.29, 30.30);
        AmapPoiCandidate station = toll("B0TOLL", "杭州北收费站", point(120.28, 30.30));
        station.setEntrancePoint(entrance);
        when(drivingRouteService.drivingRoutes(any())).thenReturn(List.of(option(route(45_000L, 2_700L))));
        when(candidateEvaluator.evaluate(any(), anyList(), any()))
                .thenReturn(WaypointRouteCandidateEvaluator.Evaluation.accepted(
                        WaypointRouteCandidateEvaluator.Reason.TOLL_ARRIVAL_MATCHED, "到达收费站"));

        List<PassengerRouteCandidate> result = service.planCandidates(
                point(120.10, 30.20), point(120.50, 30.40), List.of(station), "TOLL_STATION");

        assertThat(result).singleElement().satisfies(candidate ->
                assertThat(candidate.routingWaypoints()).containsExactly(entrance));
        ArgumentCaptor<RouteRequest> requestCaptor = ArgumentCaptor.forClass(RouteRequest.class);
        verify(drivingRouteService).drivingRoutes(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getWaypoints()).containsExactly(entrance);
    }

    @Test
    void ambiguousMapEvidenceDoesNotBecomeDirectionClarification() {
        AmapPoiCandidate serviceArea = waypoint("B0AMBIGUOUS", "下沙服务区",
                point(120.29, 30.30), point(120.30, 30.30));
        when(drivingRouteService.drivingRoutes(any())).thenReturn(List.of(option(route(50_000L, 3_000L))));
        when(candidateEvaluator.evaluate(any(), anyList(), any()))
                .thenReturn(WaypointRouteCandidateEvaluator.Evaluation.ambiguous(
                        WaypointRouteCandidateEvaluator.Reason.SITE_ARRIVAL_NOT_PROVEN, "缺少到达事件"));

        assertThatThrownBy(() -> service.planCandidates(
                point(120.10, 30.20), point(120.50, 30.40), List.of(serviceArea), "SERVICE_AREA"))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("暂无法确认路线是否经过指定地点");
    }

    @Test
    void planningFailureIsReportedWhenEveryPoiRequestFails() {
        AmapPoiCandidate serviceArea = waypoint("B0FAILED", "下沙服务区",
                point(120.29, 30.30), point(120.30, 30.30));
        when(drivingRouteService.drivingRoutes(any()))
                .thenThrow(new AmapApiException("高德路径规划失败: USER_DAILY_QUERY_OVER_LIMIT"));

        assertThatThrownBy(() -> service.planCandidates(
                point(120.10, 30.20), point(120.50, 30.40), List.of(serviceArea), "SERVICE_AREA"))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("指定途经点路线规划失败")
                .hasMessageContaining("USER_DAILY_QUERY_OVER_LIMIT");
        verify(candidateEvaluator, never()).evaluate(any(), anyList(), any());
    }

    @Test
    void startAndEndMustBeKnownBeforeAnyMapCall() {
        AmapPoiCandidate station = toll("B0TOLL", "五常收费站", point(120.046785, 30.279236));

        assertThatThrownBy(() -> service.planCandidates(
                point(120.10, 30.20), null, List.of(station), "TOLL_STATION"))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("终点缺少有效经纬度");
        verify(coordinateConvertService, never()).convertWgs84ToAmap(anyList());
        verify(drivingRouteService, never()).drivingRoutes(any());
    }

    private static AmapPoiCandidate toll(String poiId, String name, Point center) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId(poiId);
        candidate.setName(name);
        candidate.setTypeCode("180200");
        candidate.setCenterPoint(center);
        return candidate;
    }

    private static AmapPoiCandidate waypoint(String poiId, String name, Point entrance, Point exit) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId(poiId);
        candidate.setName(name);
        candidate.setEntrancePoint(entrance);
        candidate.setExitPoint(exit);
        return candidate;
    }

    private static AmapDrivingRouteService.NavigationStep step(String road, String instruction,
                                                               String assistantAction, Point... polyline) {
        return new AmapDrivingRouteService.NavigationStep(instruction, "", assistantAction, "", road,
                List.of(polyline));
    }

    private static AmapDrivingRouteService.DrivingRouteOption optionWithSteps(long distanceMeters,
                                                                              long durationSeconds,
                                                                              AmapDrivingRouteService.NavigationStep... steps) {
        RouteResponse route = route(distanceMeters, durationSeconds);
        List<Point> polyline = new ArrayList<>();
        for (AmapDrivingRouteService.NavigationStep step : steps) {
            polyline.addAll(step.polyline());
        }
        route.setPolyline(polyline);
        return new AmapDrivingRouteService.DrivingRouteOption(route, List.of(steps));
    }

    private static AmapDrivingRouteService.DrivingRouteOption option(RouteResponse route) {
        return new AmapDrivingRouteService.DrivingRouteOption(route, List.of());
    }

    private static RouteResponse route(long distanceMeters, long durationSeconds) {
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(distanceMeters);
        route.setDurationSeconds(durationSeconds);
        route.setPolyline(List.of(point(120.10, 30.20), point(120.50, 30.40)));
        return route;
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }
}
