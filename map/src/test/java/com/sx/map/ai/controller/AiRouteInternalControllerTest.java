package com.sx.map.ai.controller;

import com.sx.map.ai.dto.ExtractEndpointsResponse;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.PassengerRouteSnapshot.BusinessType;
import com.sx.map.ai.model.PassengerRouteSnapshot.GeoPoint;
import com.sx.map.ai.model.PassengerRouteSnapshot.Place;
import com.sx.map.ai.service.PassengerCurrentRouteCheckService;
import com.sx.map.ai.service.PassengerCurrentRouteClarificationCache;
import com.sx.map.ai.service.PassengerCurrentRouteCheckService.TargetSummary;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService.EndpointFeedback;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService.PreparationResult;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService.PreparationStatus;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.CandidateSummary;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Status;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceRole;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceSummary;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceTask;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.Choice;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.PlaceCandidate;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache;
import com.sx.map.ai.service.PassengerDefaultRouteService;
import com.sx.map.ai.service.PassengerRouteConfirmationService;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService;
import com.sx.map.ai.service.PassengerRouteExtractionService;
import com.sx.map.ai.service.RouteCardAssembler;
import com.sx.map.ai.service.PassengerRouteSnapshotCache;
import com.sx.map.ai.tool.PassengerRoutePoiTools;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.CurrentRoutePassageService;
import com.sx.map.common.exception.GlobalExceptionHandler;
import com.sx.map.exception.AmapApiException;
import com.sx.map.exception.AmapRouteNotFoundException;
import com.sx.map.exception.AiRouteBusinessException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部 AI 路线接口的错误契约：业务失败使用真实 HTTP 状态，稳定错误码经
 * X-Ai-Error-Code 响应头传递，passenger-api 依赖该头形成 turn.failed，不解析响应体。
 */
class AiRouteInternalControllerTest {

    private static final String ERROR_CODE_HEADER = "X-Ai-Error-Code";

    private final PassengerRouteExtractionService extractionService = mock(PassengerRouteExtractionService.class);
    private final PassengerRouteEndpointPreparationService preparationService =
            mock(PassengerRouteEndpointPreparationService.class);
    private final PassengerRouteConfirmationService confirmationService =
            mock(PassengerRouteConfirmationService.class);
    private final PassengerDefaultRouteService defaultRouteService = mock(PassengerDefaultRouteService.class);
    private final RouteCardAssembler routeCardAssembler = mock(RouteCardAssembler.class);
    private final PassengerRouteSnapshotCache snapshotCache = mock(PassengerRouteSnapshotCache.class);
    private final PassengerCurrentRouteCheckService currentRouteCheckService =
            mock(PassengerCurrentRouteCheckService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AiRouteInternalController(extractionService,
                        preparationService, confirmationService, defaultRouteService, routeCardAssembler,
                        snapshotCache, currentRouteCheckService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void replayMissingSnapshotReturnsNullCardAndPassesTrustedScope() throws Exception {
        when(snapshotCache.findForReplay("route-ref", 10001L, "AIC-1"))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/internal/ai/route/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":10001,\"conversationNo\":\"AIC-1\",\"routeRef\":\"route-ref\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeCard").value(org.hamcrest.Matchers.nullValue()));
        verify(snapshotCache).findForReplay("route-ref", 10001L, "AIC-1");
    }

    @Test
    void invalidPlaceChoiceDoesNotReturnMapIdentity() throws Exception {
        mvc.perform(post("/api/v1/internal/ai/route/place-choice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":10001,\"conversationNo\":\"AIC-1\","
                                + "\"taskNo\":\"AIT-1\",\"placeChoiceId\":\"PC-1\",\"conditionVersion\":1,"
                                + "\"choiceRequestVersion\":1,\"currentRequestVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.placeChoices").value(org.hamcrest.Matchers.nullValue()));
        verify(preparationService).select(10001L, "AIC-1", "AIT-1", "PC-1", 1L, 1L, 3L);
    }

    @Test
    void extractReturnsModelExtractionResult() throws Exception {
        when(extractionService.extract(any())).thenReturn(new ExtractEndpointsResponse(
                "德清高速路口", "西溪湿地", null, null, "DEFAULT_ROUTE"));

        mvc.perform(post("/api/v1/internal/ai/route/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userText\":\"从德清高速路口到西溪湿地\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.originName").value("德清高速路口"))
                .andExpect(jsonPath("$.data.destinationName").value("西溪湿地"));
    }

    @Test
    void currentRouteCheckReturnsVerdictWithoutMapIdentity() throws Exception {
        when(currentRouteCheckService.check(10001L, "AIC-1", "route-ref", 3L,
                "五常收费站", "杭州", CurrentRouteCheckMode.UNSPECIFIED,
                "这条路线经过五常收费站吗"))
                .thenReturn(new PassengerCurrentRouteCheckService.Result(
                PassengerCurrentRouteCheckService.Status.CHECKED,
                CurrentRoutePassageService.Verdict.PASSED,
                "TOLL_ARRIVAL_MATCHED", "目标收费站附近有对应互通的到达收费站事件",
                new TargetSummary("五常收费站", "收费站", "杭州", "余杭区",
                        BusinessType.TOLL_STATION), null, List.of(), null));

        mvc.perform(post("/api/v1/internal/ai/route/current-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":10001,"conversationNo":"AIC-1","routeRef":"route-ref",
                                 "requestVersion":3,"placeName":"五常收费站","placeRegion":"杭州",
                                 "originalUserText":"这条路线经过五常收费站吗"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHECKED"))
                .andExpect(jsonPath("$.data.verdict").value("PASSED"))
                .andExpect(jsonPath("$.data.target.name").value("五常收费站"))
                .andExpect(jsonPath("$.data.target.businessType").value("TOLL_STATION"))
                .andExpect(jsonPath("$.data.target.placeId").doesNotExist())
                .andExpect(jsonPath("$.data.target.coordinate").doesNotExist());
    }

    @Test
    void currentRouteCheckRequiresConfirmationWhenMapCanonicalNameDiffers() {
        PassengerRouteSnapshotCache routeCache = mock(PassengerRouteSnapshotCache.class);
        PassengerRoutePoiTools poiTools = mock(PassengerRoutePoiTools.class);
        PassengerRoutePlaceChoiceCache choiceCache = mock(PassengerRoutePlaceChoiceCache.class);
        PassengerCurrentRouteClarificationCache clarificationCache =
                mock(PassengerCurrentRouteClarificationCache.class);
        CurrentRoutePassageService passageService = mock(CurrentRoutePassageService.class);
        PassengerCurrentRouteCheckService service = new PassengerCurrentRouteCheckService(
                routeCache, poiTools, choiceCache, clarificationCache, passageService);
        Place origin = new Place("O-1", "德清收费站", "德清县", "湖州",
                new GeoPoint(119.9, 30.5));
        Place destination = new Place("D-1", "西溪湿地", "西湖区", "杭州",
                new GeoPoint(120.0, 30.3));
        Instant generatedAt = Instant.now().minusSeconds(30);
        PassengerRouteSnapshot snapshot = new PassengerRouteSnapshot(
                "route-ref", 10001L, "AIC-1", 3L, origin, destination,
                "gaode", PassengerRouteSnapshot.COORDINATE_SYSTEM_GCJ02,
                PassengerRouteSnapshot.TRAVEL_MODE_DRIVING, PassengerRouteSnapshot.RouteKind.DEFAULT,
                List.of(new GeoPoint(119.9, 30.5), new GeoPoint(120.0, 30.3)),
                List.of(new PassengerRouteSnapshot.NavigationStep(
                        "向前行驶", "直行", "", "东", "测试道路", List.of())),
                40_000L, 3_000L, generatedAt, generatedAt.plusSeconds(300),
                null, List.of(), null);
        when(routeCache.findForReplay("route-ref", 10001L, "AIC-1"))
                .thenReturn(Optional.of(snapshot));
        when(routeCache.find("route-ref", 10001L, "AIC-1", 3L))
                .thenReturn(Optional.of(snapshot));

        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId("B0TEST001");
        candidate.setName("三墩收费站");
        candidate.setType("交通设施服务;收费站");
        candidate.setTypeCode("180200");
        candidate.setCity("杭州");
        candidate.setAddress("西湖区");
        Point center = new Point();
        center.setLng(120.05);
        center.setLat(30.32);
        candidate.setCenterPoint(center);
        when(poiTools.searchCurrentRouteCheckCandidates("三墩高速路口", null))
                .thenReturn(List.of(candidate));
        when(poiTools.currentRouteCheckBusinessType(candidate)).thenReturn(BusinessType.TOLL_STATION);

        String choiceId = "PC-00000000-0000-4000-8000-000000000001";
        String taskNo = "AIT-00000000-0000-4000-8000-000000000001";
        Instant expiresAt = Instant.now().plusSeconds(300);
        Place selectedPlace = new Place(candidate.getPoiId(), candidate.getName(), candidate.getAddress(),
                candidate.getCity(), new GeoPoint(120.05, 30.32));
        PlaceCandidate cachedCandidate = new PlaceCandidate(selectedPlace, BusinessType.TOLL_STATION,
                List.of(), candidate.getType());
        ChoiceTask task = new ChoiceTask(taskNo, 10001L, "AIC-1", 3L, 5L,
                ChoiceRole.WAYPOINT, PassengerRouteIntent.CURRENT_ROUTE_CHECK,
                "这条路线经过三墩高速路口吗", origin, destination, null, null,
                List.of(new Choice(choiceId, cachedCandidate)), Instant.now(), expiresAt);
        when(choiceCache.saveCurrentRouteCheck(anyLong(), any(), anyLong(), anyLong(), any(),
                any(), any(), any(), any())).thenReturn(task);

        PassengerCurrentRouteCheckService.Result result = service.check(
                10001L, "AIC-1", "route-ref", 5L,
                "三墩高速路口", null, "这条路线经过三墩高速路口吗");

        assertThat(result.status()).isEqualTo(PassengerCurrentRouteCheckService.Status.NEEDS_PLACE_CHOICE);
        assertThat(result.choices()).extracting(ChoiceSummary::name).containsExactly("三墩收费站");
        verifyNoInteractions(passageService);
    }

    @Test
    void currentRoutePlaceChoiceContinuesWithOpaqueIdentifiers() throws Exception {
        when(currentRouteCheckService.checkSelected(10001L, "AIC-1", "route-ref",
                3L, 5L, "AIT-1", "PC-1"))
                .thenReturn(new PassengerCurrentRouteCheckService.Result(
                        PassengerCurrentRouteCheckService.Status.CONTEXT_UNAVAILABLE,
                        null, null, "当前路线不存在、已过期或与会话条件不一致",
                        null, null, List.of(), null));

        mvc.perform(post("/api/v1/internal/ai/route/current-check/place-choice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":10001,"conversationNo":"AIC-1","routeRef":"route-ref",
                                 "choiceRequestVersion":3,"currentRequestVersion":5,
                                 "taskNo":"AIT-1","placeChoiceId":"PC-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.placeChoices").value(org.hamcrest.Matchers.nullValue()));
        verify(currentRouteCheckService).checkSelected(10001L, "AIC-1", "route-ref",
                3L, 5L, "AIT-1", "PC-1");
    }

    @Test
    void prepareReturnsOpaquePlaceChoicesWithoutMapIdentity() throws Exception {
        Instant expiresAt = Instant.parse("2026-09-18T13:00:00Z");
        when(preparationService.prepare(anyLong(), any(), any(), anyLong(),
                any(), any(), any(), any(), any(), any())).thenReturn(new PreparationResult(
                PreparationStatus.NEEDS_PLACE_CHOICE,
                new EndpointFeedback(Status.NEEDS_CLARIFICATION,
                        List.of(new CandidateSummary("德清", "交通设施", "湖州", "德清县", "武康街道"))),
                new EndpointFeedback(Status.UNIQUE,
                        List.of(new CandidateSummary("杭州东站", null, "杭州", null, "天城路"))),
                ChoiceRole.ORIGIN, "AIT-example",
                List.of(new ChoiceSummary("PC-example", "德清", "交通设施",
                        "湖州", "武康街道", null, expiresAt)), expiresAt));

        mvc.perform(post("/api/v1/internal/ai/route/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":10001,"conversationNo":"AIC-1","requestNo":"REQ-1",
                                 "conditionVersion":1,"originName":"德清","destinationName":"杭州东站",
                                 "intent":"DEFAULT_ROUTE","originalUserText":"规划德清到杭州东站的路线"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_PLACE_CHOICE"))
                .andExpect(jsonPath("$.data.placeChoices.taskNo").value("AIT-example"))
                .andExpect(jsonPath("$.data.placeChoices.role").value("ORIGIN"))
                .andExpect(jsonPath("$.data.placeChoices.choices[0].placeChoiceId").value("PC-example"))
                .andExpect(jsonPath("$.data.placeChoices.choices[0].city").value("湖州"))
                .andExpect(jsonPath("$.data.placeChoices.choices[0].placeId").doesNotExist())
                .andExpect(jsonPath("$.data.placeChoices.choices[0].coordinate").doesNotExist());
    }

    @Test
    void defaultRouteWithoutReachableRouteReturns422WithStableCode() throws Exception {
        when(defaultRouteService.createAndCache(anyLong(), any(), anyLong()))
                .thenThrow(new AmapRouteNotFoundException("无可用驾车路线"));

        mvc.perform(post("/api/v1/internal/ai/route/default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":10001,\"conversationNo\":\"AIC-1\",\"conditionVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(ERROR_CODE_HEADER, "AI_ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void defaultRouteMapFailureReturns503WithStableCode() throws Exception {
        // 算路故障与"没有可达路线"必须区分：前者可重试，后者是业务结论。
        when(defaultRouteService.createAndCache(anyLong(), any(), anyLong()))
                .thenThrow(new AmapApiException("高德算路失败"));

        mvc.perform(post("/api/v1/internal/ai/route/default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":10001,\"conversationNo\":\"AIC-1\",\"conditionVersion\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(ERROR_CODE_HEADER, "AI_MAP_UNAVAILABLE"))
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void expiredRouteContextReturns409WithStableCode() throws Exception {
        when(defaultRouteService.createAndCache(anyLong(), any(), anyLong()))
                .thenThrow(new AiRouteBusinessException("AI_ROUTE_CONTEXT_UNAVAILABLE",
                        HttpStatus.CONFLICT, "已确认起终点不存在或已失效"));

        mvc.perform(post("/api/v1/internal/ai/route/default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":10001,\"conversationNo\":\"AIC-1\",\"conditionVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(ERROR_CODE_HEADER, "AI_ROUTE_CONTEXT_UNAVAILABLE"));
    }

    @Test
    void dtoConstructorValidationReturns400WithStableCode() throws Exception {
        mvc.perform(post("/api/v1/internal/ai/route/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":0,"conversationNo":"AIC-1","requestNo":"REQ-1",
                                 "conditionVersion":1,"originName":"A","destinationName":"B",
                                 "intent":"DEFAULT_ROUTE","originalUserText":"从A到B"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(ERROR_CODE_HEADER, "AI_INVALID_REQUEST"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void unexpectedInternalFailureReturns500WithStableCode() throws Exception {
        when(extractionService.extract(any())).thenThrow(new IllegalStateException("unexpected"));

        mvc.perform(post("/api/v1/internal/ai/route/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userText\":\"从A到B\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(ERROR_CODE_HEADER, "AI_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void preparePoiLookupFailureReturns503WithStableCode() throws Exception {
        when(preparationService.prepare(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AmapApiException("POI 查询失败"));

        mvc.perform(post("/api/v1/internal/ai/route/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":10001,"conversationNo":"AIC-1","requestNo":"REQ-1",
                                 "conditionVersion":1,"originName":"德清高速路口","destinationName":"西溪湿地",
                                 "intent":"DEFAULT_ROUTE","originalUserText":"从德清高速路口到西溪湿地"}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(ERROR_CODE_HEADER, "AI_POI_LOOKUP_FAILED"));
    }

    @Test
    void unknownIntentIsNormalizedToDefaultRoute() throws Exception {
        // 模型输出不可信：未知意图在进入服务前收敛为默认路线规划，不能阻断主干。
        when(preparationService.prepare(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AmapApiException("到此为止，只验证传入意图"));

        mvc.perform(post("/api/v1/internal/ai/route/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":10001,"conversationNo":"AIC-1","requestNo":"REQ-1",
                                 "conditionVersion":1,"originName":"A","destinationName":"B",
                                 "intent":"MODEL_INVENTED_INTENT","originalUserText":"从A到B"}"""))
                .andExpect(status().isServiceUnavailable());

        ArgumentCaptor<PassengerRouteIntent> intentCaptor = ArgumentCaptor.forClass(PassengerRouteIntent.class);
        verify(preparationService).prepare(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(),
                intentCaptor.capture(), any());
        assertThat(intentCaptor.getValue()).isEqualTo(PassengerRouteIntent.DEFAULT_ROUTE);
    }
}
