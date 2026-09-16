package com.sx.map.ai.tool;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.model.dto.WaypointRoutePlanResponse;
import com.sx.map.service.WaypointRoutePlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PassengerRoutePlanningToolsTest {

    private PassengerRoutePoiTools poiTools;
    private WaypointRoutePlanningService routePlanningService;
    private PassengerRoutePlanningTools tools;

    @BeforeEach
    void setUp() {
        poiTools = mock(PassengerRoutePoiTools.class);
        routePlanningService = mock(WaypointRoutePlanningService.class);
        tools = new PassengerRoutePlanningTools(poiTools, routePlanningService);
    }

    @Test
    void plansOnlyWithCandidateReturnedByLatestPoiSearch() {
        Point origin = point(120.2156, 30.2525);
        Point destination = point(120.8005, 30.6906);
        AmapPoiCandidate selected = candidate("B0SELECTED", "下沙服务区(沪昆高速上海方向)");
        when(poiTools.searchWaypointCandidates("下沙服务区", "杭州", "SERVICE_AREA"))
                .thenReturn(List.of(candidate("B0OTHER", "下沙服务区(沪昆高速昆明方向)"), selected));

        WaypointRoutePlanResponse fullResult = result(selected);
        when(routePlanningService.plan(
                origin,
                destination,
                selected,
                WaypointRoutePlanningService.WaypointAccess.ENTRANCE))
                .thenReturn(fullResult);
        AtomicReference<WaypointRoutePlanResponse> resultReference = new AtomicReference<>();

        Map<String, Object> summary = tools.planRouteViaSelectedWaypoint(
                "下沙服务区",
                "杭州",
                "SERVICE_AREA",
                " B0SELECTED ",
                "entrance",
                toolContext(origin, destination, resultReference));

        assertThat(resultReference.get()).isSameAs(fullResult);
        assertThat(summary)
                .containsEntry("poiId", "B0SELECTED")
                .containsEntry("distanceMeters", 98_623L)
                .containsEntry("durationSeconds", 5_804L)
                .containsEntry("distanceDeltaMeters", 15_846L)
                .containsEntry("durationDeltaSeconds", 1_410L)
                .containsEntry("comparisonAvailable", true)
                .containsEntry("polylineAvailable", true)
                .doesNotContainKeys("polyline", "route", "referenceRoute");
        verify(routePlanningService).plan(
                origin,
                destination,
                selected,
                WaypointRoutePlanningService.WaypointAccess.ENTRANCE);
    }

    /**
     * 模型提交的 POI ID 必须出现在最新高德候选中，不能把任意字符串或旧候选交给算路服务。
     */
    @Test
    void rejectsPoiIdThatIsNotInLatestCandidates() {
        when(poiTools.searchWaypointCandidates("下沙服务区", "杭州", "SERVICE_AREA"))
                .thenReturn(List.of(candidate("B0CURRENT", "下沙服务区")));

        assertThatThrownBy(() -> tools.planRouteViaSelectedWaypoint(
                "下沙服务区",
                "杭州",
                "SERVICE_AREA",
                "B0FORGED",
                "ENTRANCE",
                validToolContext()))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("不在最新高德候选中");

        verifyNoInteractions(routePlanningService);
    }

    /**
     * 上下文不完整时必须在二次 POI 查询之前失败，避免无效请求消耗高德调用额度。
     */
    @Test
    void rejectsMissingTrustedDestinationBeforeCallingExternalServices() {
        AtomicReference<WaypointRoutePlanResponse> resultReference = new AtomicReference<>();
        ToolContext context = new ToolContext(Map.of(
                PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY, point(120.2156, 30.2525),
                PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY, resultReference
        ));

        assertThatThrownBy(() -> tools.planRouteViaSelectedWaypoint(
                "下沙服务区",
                "杭州",
                "SERVICE_AREA",
                "B0SELECTED",
                "ENTRANCE",
                context))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("页面终点");

        verifyNoInteractions(poiTools, routePlanningService);
    }

    @Test
    void rejectsInvalidAccessRoleBeforeCallingExternalServices() {
        assertThatThrownBy(() -> tools.planRouteViaSelectedWaypoint(
                "下沙服务区",
                "杭州",
                "SERVICE_AREA",
                "B0SELECTED",
                "CENTER",
                validToolContext()))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("仅支持 ENTRANCE 或 EXIT");

        verify(poiTools, never()).searchWaypointCandidates("下沙服务区", "杭州", "SERVICE_AREA");
        verifyNoInteractions(routePlanningService);
    }

    /**
     * 使用 Spring AI 自己生成工具定义，确保可信坐标与完整结果接收器不会进入模型可见参数。
     */
    @Test
    void generatedToolSchemaDoesNotExposeTrustedContext() {
        var callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name())
                .isEqualTo("plan_route_via_selected_waypoint");
        assertThat(callbacks[0].getToolDefinition().inputSchema())
                .contains("keywords", "region", "poiType", "poiId", "waypointAccess")
                .doesNotContain(
                        "toolContext",
                        PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY,
                        PassengerRoutePlanningTools.DESTINATION_CONTEXT_KEY,
                        PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY
                );
    }

    private static ToolContext validToolContext() {
        return toolContext(
                point(120.2156, 30.2525),
                point(120.8005, 30.6906),
                new AtomicReference<>()
        );
    }

    private static ToolContext toolContext(Point origin,
                                           Point destination,
                                           AtomicReference<WaypointRoutePlanResponse> resultReference) {
        return new ToolContext(Map.of(
                PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY, origin,
                PassengerRoutePlanningTools.DESTINATION_CONTEXT_KEY, destination,
                PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY, resultReference
        ));
    }

    private static AmapPoiCandidate candidate(String poiId, String name) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId(poiId);
        candidate.setName(name);
        return candidate;
    }

    private static WaypointRoutePlanResponse result(AmapPoiCandidate waypoint) {
        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(98_623L);
        route.setDurationSeconds(5_804L);
        route.setPolyline(List.of(point(120.2156, 30.2525), point(120.8005, 30.6906)));

        RouteResponse reference = new RouteResponse();
        reference.setDistanceMeters(82_777L);
        reference.setDurationSeconds(4_394L);

        WaypointRoutePlanResponse result = new WaypointRoutePlanResponse();
        result.setWaypoint(waypoint);
        result.setWaypointAccess("ENTRANCE");
        result.setRoute(route);
        result.setReferenceRoute(reference);
        result.setDistanceDeltaMeters(15_846L);
        result.setDurationDeltaSeconds(1_410L);
        return result;
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }
}
