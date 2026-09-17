package com.sx.map.ai.tool;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.PassengerRouteCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.service.WaypointRoutePlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

    /**
     * 编排层已经完成 POI 查询时，模型只看到解释路线所需的地图事实。
     */
    @Test
    void plansFromTrustedPoiCandidatesWithoutRepeatingPoiSearch() {
        Point origin = point(120.2156, 30.2525);
        Point destination = point(120.8005, 30.6906);
        List<AmapPoiCandidate> trustedCandidates = List.of(
                waypoint("B0WEST", "下沙服务区(西侧)"),
                waypoint("B0EAST", "下沙服务区(东侧)")
        );
        List<PassengerRouteCandidate> fullResult = List.of(routeCandidate(trustedCandidates.get(1)));
        when(routePlanningService.planCandidates(origin, destination, trustedCandidates, "SERVICE_AREA"))
                .thenReturn(fullResult);
        AtomicReference<List<PassengerRouteCandidate>> resultReference = new AtomicReference<>();

        List<Map<String, Object>> summaries = tools.planRouteCandidates(
                "下沙服务区",
                "杭州",
                "SERVICE_AREA",
                toolContext(origin, destination, trustedCandidates, resultReference)
        );

        assertThat(resultReference.get()).containsExactlyElementsOf(fullResult);
        assertThat(summaries).singleElement().satisfies(summary -> assertThat(summary)
                .containsEntry("poiName", "下沙服务区(东侧)")
                .containsEntry("passageMode", PassengerRouteCandidate.PASSAGE_MODE_ENTER_AND_EXIT)
                .containsEntry("distanceMeters", 98_623L)
                .containsEntry("durationSeconds", 5_804L)
                .containsEntry("passageVerified", true)
                .containsOnlyKeys("poiName", "passageMode", "distanceMeters", "durationSeconds",
                        "passageVerified"));
        assertThat(resultReference.get().getFirst().routeCandidateId()).isEqualTo("PRC-test");
        assertThat(resultReference.get().getFirst().route().getPolyline()).hasSize(2);
        verifyNoInteractions(poiTools);
        verify(routePlanningService).planCandidates(origin, destination, trustedCandidates, "SERVICE_AREA");
    }

    /**
     * 当前编排尚未注入 POI 集合时，工具仍可在 Java 内部受控查询并验证路线。
     */
    @Test
    void performsControlledPoiSearchWhenTrustedCandidatesAreAbsent() {
        Point origin = point(120.2156, 30.2525);
        Point destination = point(120.8005, 30.6906);
        List<AmapPoiCandidate> searchedCandidates = List.of(waypoint("B0EAST", "下沙服务区"));
        List<PassengerRouteCandidate> result = List.of(routeCandidate(searchedCandidates.getFirst()));
        when(poiTools.searchWaypointCandidates("下沙服务区", "杭州", "SERVICE_AREA"))
                .thenReturn(searchedCandidates);
        when(routePlanningService.planCandidates(origin, destination, searchedCandidates, "SERVICE_AREA"))
                .thenReturn(result);
        AtomicReference<List<PassengerRouteCandidate>> resultReference = new AtomicReference<>();

        List<Map<String, Object>> summaries = tools.planRouteCandidates(
                " 下沙服务区 ",
                "杭州",
                "SERVICE_AREA",
                toolContext(origin, destination, null, resultReference)
        );

        assertThat(summaries).hasSize(1);
        assertThat(resultReference.get()).containsExactlyElementsOf(result);
        verify(poiTools).searchWaypointCandidates("下沙服务区", "杭州", "SERVICE_AREA");
        verify(routePlanningService).planCandidates(origin, destination, searchedCandidates, "SERVICE_AREA");
    }

    /**
     * 上下文不完整时必须在可能计费的POI查询之前失败，避免无效请求消耗高德调用额度。
     */
    @Test
    void rejectsMissingTrustedDestinationBeforeCallingExternalServices() {
        AtomicReference<List<PassengerRouteCandidate>> resultReference = new AtomicReference<>(
                List.of(routeCandidate(waypoint("B0OLD", "旧路线"))));
        ToolContext context = new ToolContext(Map.of(
                PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY, point(120.2156, 30.2525),
                PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY, resultReference
        ));

        assertThatThrownBy(() -> tools.planRouteCandidates(
                "下沙服务区",
                "杭州",
                "SERVICE_AREA",
                context
        ))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("页面终点");

        verifyNoInteractions(poiTools, routePlanningService);
        assertThat(resultReference.get()).isNull();
    }

    @Test
    void clearsPreviousResultWhenCurrentPlanningFails() {
        Point origin = point(120.2156, 30.2525);
        Point destination = point(120.8005, 30.6906);
        AmapPoiCandidate station = waypoint("B0WUCHANG", "五常收费站");
        List<AmapPoiCandidate> candidates = List.of(station);
        AtomicReference<List<PassengerRouteCandidate>> resultReference = new AtomicReference<>(
                List.of(routeCandidate(waypoint("B0OLD", "旧路线"))));
        when(routePlanningService.planCandidates(origin, destination, candidates, "TOLL_STATION"))
                .thenThrow(new AmapApiException("地图证据不足，暂无法确认路线是否经过指定地点"));

        assertThatThrownBy(() -> tools.planRouteCandidates(
                "五常收费站", "杭州", "TOLL_STATION",
                toolContext(origin, destination, candidates, resultReference)))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("暂无法确认");
        assertThat(resultReference.get()).isNull();
        verifyNoInteractions(poiTools);
    }

    @Test
    void emptyPlanningResultCannotBeTreatedAsACompletedRoute() {
        Point origin = point(120.2156, 30.2525);
        Point destination = point(120.8005, 30.6906);
        List<AmapPoiCandidate> candidates = List.of(waypoint("B0WUCHANG", "五常收费站"));
        AtomicReference<List<PassengerRouteCandidate>> resultReference = new AtomicReference<>();
        when(routePlanningService.planCandidates(origin, destination, candidates, "TOLL_STATION"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> tools.planRouteCandidates(
                "五常收费站", "杭州", "TOLL_STATION",
                toolContext(origin, destination, candidates, resultReference)))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("没有已验证经过指定地点的路线");
        assertThat(resultReference.get()).isNull();
    }

    /**
     * Spring AI生成的JSON Schema只能包含模型可提供的业务文本，不能暴露坐标、POI ID和结果容器。
     */
    @Test
    void generatedToolSchemaOnlyExposesRouteIntentFields() {
        var callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("plan_route_candidates");
        assertThat(callbacks[0].getToolDefinition().description())
                .contains("预计总里程和行驶时间")
                .doesNotContain("方向正确", "时间差");
        assertThat(callbacks[0].getToolDefinition().inputSchema())
                .contains("keywords", "region", "poiType")
                .doesNotContain(
                        "poiId",
                        "routeCandidateId",
                        "currentRouteRef",
                        "waypointAccess",
                        "toolContext",
                        PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY,
                        PassengerRoutePlanningTools.DESTINATION_CONTEXT_KEY,
                        PassengerRoutePlanningTools.POI_CANDIDATES_CONTEXT_KEY,
                        PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY
                );
    }

    private static ToolContext toolContext(Point origin,
                                           Point destination,
                                           List<AmapPoiCandidate> candidates,
                                           AtomicReference<List<PassengerRouteCandidate>> resultReference) {
        if (candidates == null) {
            return new ToolContext(Map.of(
                    PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY, origin,
                    PassengerRoutePlanningTools.DESTINATION_CONTEXT_KEY, destination,
                    PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY, resultReference
            ));
        }
        return new ToolContext(Map.of(
                PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY, origin,
                PassengerRoutePlanningTools.DESTINATION_CONTEXT_KEY, destination,
                PassengerRoutePlanningTools.POI_CANDIDATES_CONTEXT_KEY, candidates,
                PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY, resultReference
        ));
    }

    private static PassengerRouteCandidate routeCandidate(AmapPoiCandidate waypoint) {
        Point entrance = point(120.3660, 30.3090);
        Point exit = point(120.3670, 30.3090);
        waypoint.setEntrancePoint(entrance);
        waypoint.setExitPoint(exit);

        RouteResponse route = new RouteResponse();
        route.setDistanceMeters(98_623L);
        route.setDurationSeconds(5_804L);
        route.setPolyline(List.of(point(120.2156, 30.2525), point(120.8005, 30.6906)));

        Instant generatedAt = Instant.parse("2026-09-16T10:00:00Z");
        return new PassengerRouteCandidate(
                "PRC-test",
                waypoint,
                List.of(entrance, exit),
                PassengerRouteCandidate.PASSAGE_MODE_ENTER_AND_EXIT,
                route,
                "SITE_ARRIVAL_MATCHED",
                generatedAt,
                generatedAt.plusSeconds(300)
        );
    }

    private static AmapPoiCandidate waypoint(String poiId, String name) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId(poiId);
        candidate.setName(name);
        return candidate;
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }
}
