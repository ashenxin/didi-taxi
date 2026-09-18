package com.sx.passengerapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sx.passengerapi.client.MapRouteAiClient;
import com.sx.passengerapi.client.PassengerAiConversationClient;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AssistantMessageView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CandidateView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.DefaultRouteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.EndpointFeedbackView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.GeoPointView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PlaceView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteCardDto;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ai.AiSseEvents.ContentEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnCompletedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnFailedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnStartedEvent;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRouteTurnServiceTest {

    private static final long CUSTOMER_ID = 10001L;
    private static final String CONVERSATION_NO = "AIC-test";

    private final PassengerAiConversationClient passengerAi = mock(PassengerAiConversationClient.class);
    private final MapRouteAiClient mapRouteAi = mock(MapRouteAiClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private AiRouteTurnService service;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        service = new AiRouteTurnService(passengerAi, mapRouteAi, objectMapper);
        sink = new RecordingSink();
    }

    @Test
    void createConversationUnwrapsDownstreamResult() {
        when(passengerAi.create(any())).thenReturn(success(new AiConversationCreateResponse("AIC-1")));

        AiConversationCreateResponse result = service.createConversation(CUSTOMER_ID, "create-1");

        assertThat(result.conversationNo()).isEqualTo("AIC-1");
        verify(passengerAi).create(new AiConversationCreateRequest(CUSTOMER_ID, "create-1"));
    }

    @Test
    void firstTurnExtractsEndpointsAndAsksConfirmationQuestion() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清高速路口", "西溪湿地", null, null, "DEFAULT_ROUTE")));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_CONFIRMATION",
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "德清高速路口", null, "湖州", null, "德清县"))),
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "西溪湿地", null, "杭州", null, "西湖区"))),
                Instant.now().plusSeconds(300))));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-2", "REQ-1", 2, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "帮我规划一条从德清高速路口到西溪湿地的路线", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("我理解的起点是德清高速路口，终点是西溪湿地，对吗？");
        ArgumentCaptor<AiTurnCompleteRequest> completeCaptor = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-1"), completeCaptor.capture());
        assertThat(completeCaptor.getValue().payloadJson())
                .contains("\"endpointConfirmation\":true")
                .contains("\"conditionVersion\":1");
    }

    @Test
    void endpointChangeUsesCurrentRequestVersionAsConditionVersion() {
        // 前一轮已确认 A→B（提问载荷 version=1）；本轮乘客改口 C→D，序号已推进到 5。
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-5", 5,
                        new AssistantMessageView("AIM-4", "REQ-1", "ASSISTANT", "TEXT",
                                "我理解的起点是A，终点是B，对吗？",
                                "{\"endpointConfirmation\":true,\"conditionVersion\":1}", 4,
                                LocalDateTime.now(), "COMPLETED", null, null),
                        null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "西溪湿地", "杭州东站", null, null, "DEFAULT_ROUTE")));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_CONFIRMATION",
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "西溪湿地", null, "杭州", null, "西湖区"))),
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "杭州东站", null, "杭州", null, "上城区"))),
                Instant.now().plusSeconds(300))));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-5"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-6", "REQ-5", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "改一下，从西溪湿地去杭州东站", "turn-3");

        ArgumentCaptor<RoutePreparationRequest> prepareCaptor = ArgumentCaptor.forClass(RoutePreparationRequest.class);
        verify(mapRouteAi).prepare(prepareCaptor.capture());
        assertThat(prepareCaptor.getValue().conditionVersion()).isEqualTo(5);
        ArgumentCaptor<AiTurnCompleteRequest> completeCaptor = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-5"), completeCaptor.capture());
        assertThat(completeCaptor.getValue().payloadJson()).contains("\"conditionVersion\":5");
    }

    @Test
    void confirmationReplyGeneratesRouteCardWithoutPersistingPolyline() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2,
                        new AssistantMessageView("AIM-2", "REQ-1", "ASSISTANT", "TEXT",
                                "我理解的起点是A，终点是B，对吗？",
                                "{\"endpointConfirmation\":true,\"conditionVersion\":1}", 2,
                                LocalDateTime.now(), "COMPLETED", null, null),
                        null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONFIRMED", null)));
        RouteCardDto card = card();
        when(mapRouteAi.defaultRoute(any())).thenReturn(success(card));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-4", "REQ-2", 4, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        ArgumentCaptor<AiTurnCompleteRequest> completeCaptor = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-2"), completeCaptor.capture());
        String persistedPayload = completeCaptor.getValue().payloadJson();
        assertThat(persistedPayload).contains("\"routeRef\"").doesNotContain("polyline");
        assertThat(sink.lastContent().assistantMessage().payload().toString()).contains("polyline");
        verify(mapRouteAi).confirm(new RouteConfirmRequest(CUSTOMER_ID, CONVERSATION_NO, "REQ-1", 1, "对"));
    }

    @Test
    void notAcceptedConfirmationAsksToReplyAgain() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("NOT_ACCEPTED", null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-4", "REQ-2", 4, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.lastContent().assistantMessage().content())
                .contains("请回复「对」确认");
        verify(mapRouteAi, never()).defaultRoute(any());
    }

    @Test
    void expiredConfirmationContextAsksForEndpointsAgain() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONTEXT_UNAVAILABLE", null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-4", "REQ-2", 4, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.lastContent().assistantMessage().content())
                .contains("已失效");
    }

    @Test
    void missingEndpointsAsksForBothEndpoints() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(
                new ExtractEndpointsResponse(null, null, null, null, "DEFAULT_ROUTE")));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-2", "REQ-1", 2, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "你好", "turn-1");

        assertThat(sink.lastContent().assistantMessage().content()).contains("请告诉我起点和终点");
        verify(mapRouteAi, never()).prepare(any());
    }

    @Test
    void idempotentReplayOnlyReemitsStoredAnswer() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null,
                        new AssistantMessageView("AIM-2", "REQ-1", "ASSISTANT", "TEXT",
                                "我理解的起点是A，终点是B，对吗？", null, 2, LocalDateTime.now(),
                                "COMPLETED", null, null))));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "帮我规划一条路线", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content()).contains("对吗");
        verify(mapRouteAi, never()).extract(any());
    }

    @Test
    void validRouteReplayRestoresPolylineFromMapCacheUnderCanonicalPayload() {
        RouteCardDto source = card();
        RouteCardDto current = new RouteCardDto(source.routeRef(), source.origin(), source.destination(),
                source.via(), source.route(), Instant.now().minusSeconds(10), Instant.now().plusSeconds(240));
        String summary = "{\"routeCard\":{\"routeRef\":\"" + current.routeRef()
                + "\",\"route\":{\"distanceMeters\":45600,\"durationSeconds\":3300}}}";
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 3, null, routeReply(summary))));
        when(mapRouteAi.replay(new RouteReplayRequest(CUSTOMER_ID, CONVERSATION_NO, current.routeRef())))
                .thenReturn(success(new RouteReplayResponse(current)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        Map<String, Object> payload = sink.lastContent().assistantMessage().payload();
        assertThat(payload).containsOnlyKeys("routeCard");
        assertThat(payload.toString()).contains("polyline").doesNotContain("expired=true");
        verify(mapRouteAi, never()).defaultRoute(any());
    }

    @Test
    void expiredRouteReplayKeepsOldFlatSummaryAndMarksExpired() {
        String routeRef = card().routeRef();
        String oldSummary = "{\"routeRef\":\"" + routeRef
                + "\",\"origin\":{\"name\":\"德清高速路口\"},"
                + "\"route\":{\"distanceMeters\":45600,\"durationSeconds\":3300}}";
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 3, null, routeReply(oldSummary))));
        when(mapRouteAi.replay(new RouteReplayRequest(CUSTOMER_ID, CONVERSATION_NO, routeRef)))
                .thenReturn(success(new RouteReplayResponse(null)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        Map<String, Object> payload = sink.lastContent().assistantMessage().payload();
        assertThat(payload).containsOnlyKeys("routeCard");
        assertThat(payload.toString()).contains("expired=true", "distanceMeters=45600", routeRef)
                .doesNotContain("polyline");
    }

    @Test
    void routeReplayMapFailureFallsBackToStoredSummary() {
        String routeRef = card().routeRef();
        String summary = "{\"routeCard\":{\"routeRef\":\"" + routeRef
                + "\",\"route\":{\"distanceMeters\":45600}}}";
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 3, null, routeReply(summary))));
        when(mapRouteAi.replay(new RouteReplayRequest(CUSTOMER_ID, CONVERSATION_NO, routeRef)))
                .thenThrow(feignError(503, "X-Ai-Error-Code", "AI_MAP_UNAVAILABLE"));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .contains("expired=true", "distanceMeters=45600");
        verify(passengerAi, never()).fail(any(), any(), any());
    }

    @Test
    void expiredBeginConflictKeepsSpecificErrorCode() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenThrow(feignError(409, "X-Ai-Error-Code", "AI_REQUEST_EXPIRED"));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "从A到B", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.failed");
        assertThat(sink.lastFailed().code()).isEqualTo("AI_REQUEST_EXPIRED");
        assertThat(sink.lastFailed().message()).contains("已超时");
    }

    @Test
    void failedReplayOnlyReemitsStoredFailure() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null,
                        new AssistantMessageView("AIM-2", "REQ-1", "ASSISTANT", "TEXT",
                                "抱歉，服务暂时不可用，请稍后重试。", null, 2, LocalDateTime.now(),
                                "FAILED", "AI_PROVIDER_UNAVAILABLE", "客服暂时没能处理这条消息，请稍后重试"))));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "从A到B", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.started", "turn.failed");
        assertThat(sink.lastFailed().code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(sink.lastFailed().message()).isEqualTo("客服暂时没能处理这条消息，请稍后重试");
        verify(mapRouteAi, never()).extract(any());
        verify(passengerAi, never()).fail(eq(CONVERSATION_NO), eq("REQ-1"), any());
    }

    @Test
    void modelCallFailureShowsFriendlyFailureInsteadOfAskingForEndpoints() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any()))
                .thenThrow(feignError(503, "X-Ai-Error-Code", "AI_PROVIDER_UNAVAILABLE"));
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        "AIM-2", "REQ-1", 2)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "从A到B", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.started", "turn.failed");
        assertThat(sink.lastFailed().code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(sink.lastFailed().message()).isEqualTo("客服暂时没能处理这条消息，请稍后重试");
        verify(passengerAi, never()).complete(eq(CONVERSATION_NO), eq("REQ-1"), any());
        ArgumentCaptor<AiTurnFailRequest> failCaptor = ArgumentCaptor.forClass(AiTurnFailRequest.class);
        verify(passengerAi).fail(eq(CONVERSATION_NO), eq("REQ-1"), failCaptor.capture());
        assertThat(failCaptor.getValue().failureMessage()).isEqualTo(sink.lastFailed().message());
    }

    @Test
    void mapTimeoutFailsTurnAndPersistsFailure() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONFIRMED", null)));
        FeignException timeout = mock(FeignException.class);
        when(timeout.status()).thenReturn(504);
        when(mapRouteAi.defaultRoute(any())).thenThrow(timeout);
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        "AIM-4", "REQ-2", 4)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "turn.failed");
        assertThat(sink.lastFailed().code()).isEqualTo("AI_MAP_TIMEOUT");
        ArgumentCaptor<AiTurnFailRequest> failCaptor = ArgumentCaptor.forClass(AiTurnFailRequest.class);
        verify(passengerAi).fail(eq(CONVERSATION_NO), eq("REQ-2"), failCaptor.capture());
        assertThat(failCaptor.getValue().failureCode()).isEqualTo("AI_MAP_TIMEOUT");
    }

    @Test
    void mapErrorCodeHeaderDrivesFailureCodeWithoutParsingBody() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONFIRMED", null)));
        // 真实 FeignException：错误码经响应头传递，不依赖响应体的数字状态码。
        when(mapRouteAi.defaultRoute(any())).thenThrow(feignError(422, "X-Ai-Error-Code", "AI_ROUTE_NOT_FOUND"));
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        "AIM-4", "REQ-2", 4)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "turn.failed");
        assertThat(sink.lastFailed().code()).isEqualTo("AI_ROUTE_NOT_FOUND");
        assertThat(sink.lastFailed().message()).contains("未找到可达路线");
        assertThat(sink.lastFailed().requestNo()).isEqualTo("REQ-2");
    }

    @Test
    void errorCodeHeaderIsMatchedCaseInsensitively() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONFIRMED", null)));
        // HTTP/2 与部分客户端会把头名规范化为小写，识别不能区分大小写。
        when(mapRouteAi.defaultRoute(any())).thenThrow(feignError(503, "x-ai-error-code", "AI_POI_LOOKUP_FAILED"));
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        "AIM-4", "REQ-2", 4)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.lastFailed().code()).isEqualTo("AI_POI_LOOKUP_FAILED");
    }

    @Test
    void unknownErrorCodeFallsBackToStatusClassification() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONFIRMED", null)));
        // 未约定的错误码不得被当作已识别原因，回落 404→会话、409→并发、504→地图，其余为内部错误。
        when(mapRouteAi.defaultRoute(any())).thenThrow(feignError(503, "X-Ai-Error-Code", "AI_UNKNOWN_CODE"));
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        "AIM-4", "REQ-2", 4)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.lastFailed().code()).isEqualTo("AI_INTERNAL_ERROR");
    }

    /** 构造真实 FeignException，覆盖 errorStatus 是否把响应头带到异常上的集成行为。 */
    private static FeignException feignError(int status, String headerName, String errorCode) {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/internal/ai/route/default",
                Map.of(), new byte[0], StandardCharsets.UTF_8);
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .headers(Map.of(headerName, List.of(errorCode)))
                .request(request)
                .build();
        return FeignException.errorStatus("MapRouteAiClient#defaultRoute", response);
    }

    private static AssistantMessageView confirmationQuestion() {
        return new AssistantMessageView("AIM-2", "REQ-1", "ASSISTANT", "TEXT",
                "我理解的起点是A，终点是B，对吗？",
                "{\"endpointConfirmation\":true,\"conditionVersion\":1}", 2, LocalDateTime.now(),
                "COMPLETED", null, null);
    }

    private static AssistantMessageView routeReply(String payloadJson) {
        return new AssistantMessageView("AIM-4", "REQ-2", "ASSISTANT", "ROUTE_CARD",
                "已为您规划路线", payloadJson, 4, LocalDateTime.now(), "COMPLETED", null, null);
    }

    private static RouteCardDto card() {
        return new RouteCardDto(
                "9f8b2a44-6c1d-4b5e-9a0f-2c3d4e5f6a7b",
                new PlaceView("德清高速路口"),
                new PlaceView("西溪湿地"),
                null,
                new RouteView("GCJ02", 45600, 3300,
                        List.of(new GeoPointView(120.1, 30.2), new GeoPointView(120.2, 30.3))),
                Instant.parse("2026-09-18T02:00:00Z"),
                Instant.parse("2026-09-18T02:05:00Z"));
    }

    private static <T> ResponseVo<T> success(T data) {
        return new ResponseVo<>(200, "success", data);
    }

    private static final class RecordingSink implements AiTurnEventSink {
        private final List<String> eventNames = new ArrayList<>();
        private ContentEvent lastContent;
        private TurnFailedEvent lastFailed;

        @Override
        public void turnStarted(TurnStartedEvent event) {
            eventNames.add("turn.started");
        }

        @Override
        public void content(String eventName, ContentEvent event) {
            eventNames.add(eventName);
            lastContent = event;
        }

        @Override
        public void turnCompleted(TurnCompletedEvent event) {
            eventNames.add("turn.completed");
        }

        @Override
        public void turnFailed(TurnFailedEvent event) {
            eventNames.add("turn.failed");
            lastFailed = event;
        }

        List<String> eventNames() {
            return eventNames;
        }

        ContentEvent lastContent() {
            return lastContent;
        }

        TurnFailedEvent lastFailed() {
            return lastFailed;
        }
    }
}
