package com.sx.map.ai.controller;

import com.sx.map.ai.dto.ExtractEndpointsResponse;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.service.PassengerDefaultRouteService;
import com.sx.map.ai.service.PassengerRouteConfirmationService;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService;
import com.sx.map.ai.service.PassengerRouteExtractionService;
import com.sx.map.ai.service.RouteCardAssembler;
import com.sx.map.ai.service.PassengerRouteSnapshotCache;
import com.sx.map.common.exception.GlobalExceptionHandler;
import com.sx.map.exception.AmapApiException;
import com.sx.map.exception.AmapRouteNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Optional;
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

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AiRouteInternalController(extractionService,
                        preparationService, confirmationService, defaultRouteService, routeCardAssembler,
                        snapshotCache))
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
    void preparePoiLookupFailureReturns503WithStableCode() throws Exception {
        when(preparationService.prepare(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AmapApiException("POI 查询失败"));

        mvc.perform(post("/api/v1/internal/ai/route/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":10001,"conversationNo":"AIC-1","confirmationRequestNo":"REQ-1",
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
                                {"customerId":10001,"conversationNo":"AIC-1","confirmationRequestNo":"REQ-1",
                                 "conditionVersion":1,"originName":"A","destinationName":"B",
                                 "intent":"MODEL_INVENTED_INTENT","originalUserText":"从A到B"}"""))
                .andExpect(status().isServiceUnavailable());

        ArgumentCaptor<PassengerRouteIntent> intentCaptor = ArgumentCaptor.forClass(PassengerRouteIntent.class);
        verify(preparationService).prepare(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(),
                intentCaptor.capture(), any());
        assertThat(intentCaptor.getValue()).isEqualTo(PassengerRouteIntent.DEFAULT_ROUTE);
    }
}
