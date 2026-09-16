package com.sx.map.service;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.model.dto.WaypointRoutePlanResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaypointRoutePlanningServiceTest {

    private AmapDrivingRouteService drivingRouteService;
    private AmapCoordinateConvertService coordinateConvertService;
    private WaypointRoutePlanningService service;

    @BeforeEach
    void setUp() {
        drivingRouteService = mock(AmapDrivingRouteService.class);
        coordinateConvertService = mock(AmapCoordinateConvertService.class);
        when(coordinateConvertService.convertWgs84ToAmap(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new WaypointRoutePlanningService(drivingRouteService, coordinateConvertService);
    }

    @Test
    void calculatesDeltasFromWaypointAndReferenceRoutes() {
        RouteResponse route = route(98_623L, 5_804L);
        RouteResponse reference = route(82_777L, 4_394L);
        when(drivingRouteService.drivingRoute(any())).thenReturn(route, reference);

        AmapPoiCandidate waypoint = waypointWithEntrance(120.366856, 30.309568);
        WaypointRoutePlanResponse response = service.plan(
                point(120.2156, 30.2525),
                point(120.8005, 30.6906),
                waypoint,
                WaypointRoutePlanningService.WaypointAccess.ENTRANCE);

        assertThat(response.getWaypoint()).isSameAs(waypoint);
        assertThat(response.getWaypointAccess()).isEqualTo("ENTRANCE");
        assertThat(response.getRoute()).isSameAs(route);
        assertThat(response.getReferenceRoute()).isSameAs(reference);
        assertThat(response.getDistanceDeltaMeters()).isEqualTo(15_846L);
        assertThat(response.getDurationDeltaSeconds()).isEqualTo(1_410L);

        ArgumentCaptor<RouteRequest> requestCaptor = ArgumentCaptor.forClass(RouteRequest.class);
        verify(drivingRouteService, times(2)).drivingRoute(requestCaptor.capture());
        List<RouteRequest> requests = requestCaptor.getAllValues();
        assertThat(requests.get(0).getWaypoint()).isSameAs(waypoint.getEntrancePoint());
        assertThat(requests.get(1).getWaypoint()).isNull();
        verify(coordinateConvertService).convertWgs84ToAmap(any());
    }

    @Test
    void keepsWaypointRouteWhenReferenceRouteFails() {
        RouteResponse route = route(98_623L, 5_804L);
        when(drivingRouteService.drivingRoute(any()))
                .thenReturn(route)
                .thenThrow(new AmapApiException("参考路线暂不可用"));

        WaypointRoutePlanResponse response = service.plan(
                point(120.2156, 30.2525),
                point(120.8005, 30.6906),
                waypointWithEntrance(120.366856, 30.309568),
                WaypointRoutePlanningService.WaypointAccess.ENTRANCE);

        assertThat(response.getRoute()).isSameAs(route);
        assertThat(response.getReferenceRoute()).isNull();
        assertThat(response.getDistanceDeltaMeters()).isNull();
        assertThat(response.getDurationDeltaSeconds()).isNull();
    }

    @Test
    void rejectsMissingRequestedAccessPointBeforeRouting() {
        AmapPoiCandidate waypoint = new AmapPoiCandidate();
        waypoint.setName("下沙服务区");

        assertThatThrownBy(() -> service.plan(
                point(120.2156, 30.2525),
                point(120.8005, 30.6906),
                waypoint,
                WaypointRoutePlanningService.WaypointAccess.EXIT))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("出口缺少有效经纬度");
        verify(drivingRouteService, never()).drivingRoute(any());
        verify(coordinateConvertService, never()).convertWgs84ToAmap(any());
    }

    private static AmapPoiCandidate waypointWithEntrance(double lng, double lat) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId("B0TEST001");
        candidate.setName("下沙服务区");
        candidate.setEntrancePoint(point(lng, lat));
        return candidate;
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }

    private static RouteResponse route(long distanceMeters, long durationSeconds) {
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(distanceMeters);
        route.setDurationSeconds(durationSeconds);
        return route;
    }
}
