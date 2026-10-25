package com.sx.passengerapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sx.passengerapi.client.MapRouteAiClient;
import com.sx.passengerapi.client.MapWeatherAiClient;
import com.sx.passengerapi.client.PassengerAiConversationClient;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AssistantMessageView;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.MessageView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CandidateView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckPlaceChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckPlaceChoiceView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckPlaceChoicesView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckModeChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckModeChoiceView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckModeChoicesView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckTargetView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.DefaultRouteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.EndpointFeedbackView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.GeoPointView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PlaceView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PlaceChoicesView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PlaceChoiceView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteCardDto;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePlaceChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PartialEndpointView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PartialEndpointCompleteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRoutePlaceChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRoutePlaceChoiceView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRoutePlaceChoicesView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRouteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRouteResponse;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastRequest;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse.Forecast;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse.Location;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRouteTurnServiceTest {

    private static final long CUSTOMER_ID = 10001L;
    private static final String CONVERSATION_NO = "AIC-test";

    private final PassengerAiConversationClient passengerAi = mock(PassengerAiConversationClient.class);
    private final MapRouteAiClient mapRouteAi = mock(MapRouteAiClient.class);
    private final MapWeatherAiClient mapWeatherAi = mock(MapWeatherAiClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private AiRouteTurnService service;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        service = new AiRouteTurnService(passengerAi, mapRouteAi, mapWeatherAi, objectMapper);
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
    void firstTurnWithResolvedEndpointsDirectlyGeneratesRouteCard() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清高速路口", "西溪湿地", null, null, "DEFAULT_ROUTE")));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_ROUTE",
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "德清高速路口", null, "湖州", null, "德清县"))),
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "西溪湿地", null, "杭州", null, "西湖区"))),
                Instant.now().plusSeconds(300))));
        RouteCardDto card = card();
        when(mapRouteAi.defaultRoute(any())).thenReturn(success(card));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-2", "REQ-1", 2, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "帮我规划一条从德清高速路口到西溪湿地的路线", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content()).contains("预计行驶");
        ArgumentCaptor<AiTurnCompleteRequest> completeCaptor = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-1"), completeCaptor.capture());
        assertThat(completeCaptor.getValue().payloadJson())
                .contains("\"routeRef\"")
                .doesNotContain("polyline", "endpointConfirmation");
        verify(mapRouteAi).defaultRoute(new DefaultRouteRequest(CUSTOMER_ID, CONVERSATION_NO, 1));
    }

    @Test
    void singleOriginIsPersistedThenDestinationCompletesRouteOnNextTurn() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清", null, null, null, "DEFAULT_ROUTE")));
        when(mapRouteAi.preparePartial(any())).thenReturn(success(new RoutePreparationResponse(
                "WAITING_FOR_OTHER_ENDPOINT",
                new EndpointFeedbackView("UNIQUE", List.of()),
                new EndpointFeedbackView("MISSING", List.of()), Instant.now().plusSeconds(300),
                null, new PartialEndpointView(1, "REQ-1", "ORIGIN", "DESTINATION",
                "德清", "DEFAULT_ROUTE", Instant.now().plusSeconds(300)))));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-2", "REQ-1", 2, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "从德清出发", "turn-1");

        ArgumentCaptor<AiTurnCompleteRequest> firstReply = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-1"), firstReply.capture());
        assertThat(firstReply.getValue().content()).contains("已记住起点", "请告诉我终点");
        assertThat(firstReply.getValue().payloadJson()).contains("partialEndpoint", "DESTINATION");

        AssistantMessageView latest = new AssistantMessageView("AIM-2", "REQ-1", "ASSISTANT",
                "TEXT", firstReply.getValue().content(), firstReply.getValue().payloadJson(), 2,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-3", 3, latest, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, "宁波站", null, null, "DEFAULT_ROUTE")));
        when(mapRouteAi.completePartial(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_ROUTE", new EndpointFeedbackView("UNIQUE", List.of()),
                new EndpointFeedbackView("UNIQUE", List.of()), Instant.now().plusSeconds(300))));
        when(mapRouteAi.defaultRoute(any())).thenReturn(success(card()));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-3"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-4", "REQ-3", 4, LocalDateTime.now())));
        sink = new RecordingSink();

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "到宁波站", "turn-2");

        ArgumentCaptor<ExtractEndpointsRequest> extract = ArgumentCaptor.forClass(ExtractEndpointsRequest.class);
        verify(mapRouteAi, times(2)).extract(extract.capture());
        assertThat(extract.getAllValues().get(1).expectedEndpointRole()).isEqualTo("DESTINATION");
        verify(mapRouteAi).completePartial(new PartialEndpointCompleteRequest(
                CUSTOMER_ID, CONVERSATION_NO, "REQ-1", 1, "REQ-3", 3,
                "DESTINATION", "宁波站", null));
        verify(mapRouteAi).defaultRoute(new DefaultRouteRequest(CUSTOMER_ID, CONVERSATION_NO, 3));
        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
    }

    @Test
    void placeChoiceIsPersistedAndFinalSelectionDirectlyGeneratesRoute() {
        String taskNo = "AIT-00000000-0000-4000-8000-000000000001";
        String choiceId = "PC-00000000-0000-4000-8000-000000000001";
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清", "杭州东站", null, null, "DEFAULT_ROUTE")));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "NEEDS_PLACE_CHOICE",
                new EndpointFeedbackView("NEEDS_CLARIFICATION", List.of()),
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "杭州东站", null, "杭州", null, "天城路"))),
                Instant.now().plusSeconds(300),
                new PlaceChoicesView(taskNo, "ORIGIN", List.of(new PlaceChoiceView(
                        choiceId, "德清", "行政区", "湖州", "德清县", Instant.now().plusSeconds(300))),
                        Instant.now().plusSeconds(300)))));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-2", "REQ-1", 2, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "规划德清到杭州东站的路线", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.started", "place.choices", "turn.completed");
        ArgumentCaptor<AiTurnCompleteRequest> first = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-1"), first.capture());
        assertThat(first.getValue().messageType()).isEqualTo("PLACE_CHOICES");
        assertThat(first.getValue().payloadJson()).contains(choiceId).contains("\"requestVersion\":1");

        AssistantMessageView latest = new AssistantMessageView("AIM-2", "REQ-1", "ASSISTANT",
                "PLACE_CHOICES", "请选择起点", first.getValue().payloadJson(), 2,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(placeChoiceHistory(latest)));
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 3, latest, null)));
        when(mapRouteAi.selectPlace(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_ROUTE",
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView("德清", null, "湖州", null, "德清县"))),
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView("杭州东站", null, "杭州", null, "天城路"))),
                Instant.now().plusSeconds(300))));
        RouteCardDto card = card();
        when(mapRouteAi.defaultRoute(any())).thenReturn(success(card));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-4", "REQ-2", 4, LocalDateTime.now())));
        sink = new RecordingSink();

        service.selectPlaceChoice(sink, CUSTOMER_ID, CONVERSATION_NO, taskNo, choiceId, 1,
                "德清", "湖州", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content()).contains("预计行驶");
        verify(mapRouteAi).selectPlace(new RoutePlaceChoiceRequest(CUSTOMER_ID, CONVERSATION_NO,
                taskNo, choiceId, 1, 1, 3, "REQ-2"));
        ArgumentCaptor<AiTurnCompleteRequest> second = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-2"), second.capture());
        assertThat(second.getValue().messageType()).isEqualTo("ROUTE_CARD");
        assertThat(second.getValue().payloadJson()).contains("\"routeRef\"").doesNotContain("polyline");
        verify(mapRouteAi).defaultRoute(new DefaultRouteRequest(CUSTOMER_ID, CONVERSATION_NO, 1));
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
                "READY_FOR_ROUTE",
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "西溪湿地", null, "杭州", null, "西湖区"))),
                new EndpointFeedbackView("UNIQUE", List.of(new CandidateView(
                        "杭州东站", null, "杭州", null, "上城区"))),
                Instant.now().plusSeconds(300))));
        when(mapRouteAi.defaultRoute(any())).thenReturn(success(card()));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-5"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-6", "REQ-5", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "改一下，从西溪湿地去杭州东站", "turn-3");

        ArgumentCaptor<RoutePreparationRequest> prepareCaptor = ArgumentCaptor.forClass(RoutePreparationRequest.class);
        verify(mapRouteAi).prepare(prepareCaptor.capture());
        assertThat(prepareCaptor.getValue().conditionVersion()).isEqualTo(5);
        verify(mapRouteAi).defaultRoute(new DefaultRouteRequest(CUSTOMER_ID, CONVERSATION_NO, 5));
        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
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
    void weatherQueryAnswersForecastWithoutRoutePreparation() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                "杭州", null, 1)));
        when(mapWeatherAi.forecast(any())).thenReturn(success(new WeatherForecastResponse(
                "OK",
                new Location("杭州市", "浙江省", "杭州市", "西湖区", "330100"),
                new Forecast("2026-10-26", "1", "多云", "小雨",
                        24, 18, "东北", "1-3"),
                null, null, "amap")));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-W", "REQ-W", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "杭州明天天气", "turn-w");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("杭州明天（10月26日，星期一）白天多云，夜间小雨，气温 18~24℃，"
                        + "东北风 1-3级。夜间有雨，出门记得带伞。");
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .contains("weather")
                .contains("2026-10-26")
                .contains("dayTemp=24")
                .contains("windDirection=东北风")
                .contains("windPower=1-3级");
        verify(mapWeatherAi).forecast(new WeatherForecastRequest("REQ-W", "杭州", null, 1));
        verify(mapRouteAi, never()).prepare(any());
        verify(mapRouteAi, never()).checkCurrentRoute(any());
    }

    @Test
    void weatherQueryWithoutCityAsksForCityAndDoesNotCallMap() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                null, null, 1)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-W", "REQ-W", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "明天天气", "turn-w");

        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("请告诉我城市，例如：杭州明天天气。");
        assertThat(sink.lastContent().assistantMessage().payload()).isNull();
        ArgumentCaptor<AiTurnCompleteRequest> completed =
                ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-W"), completed.capture());
        assertThat(completed.getValue().payloadJson())
                .contains("\"weatherContext\"")
                .contains("\"dayOffset\":1");
        verify(mapWeatherAi, never()).forecast(any());
    }

    @Test
    void weatherAskWithDateInTextPersistsDayOffset() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                null, null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-W", "REQ-W", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "后天会下雨吗", "turn-w");

        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("请告诉我城市，例如：杭州后天天气。");
        assertThat(sink.lastContent().assistantMessage().payload()).isNull();
        ArgumentCaptor<AiTurnCompleteRequest> completed =
                ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-W"), completed.capture());
        assertThat(completed.getValue().payloadJson())
                .contains("\"weatherContext\"")
                .contains("\"dayOffset\":2");
        verify(mapWeatherAi, never()).forecast(any());
    }

    @Test
    void weatherCityReplyAfterAskResumesPendingWeather() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W2", 6,
                        weatherAskReply(2), null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清", null, null, null, "DEFAULT_ROUTE",
                null, null, null, null, null, null, null, null)));
        when(mapWeatherAi.forecast(any())).thenReturn(success(new WeatherForecastResponse(
                "OK",
                new Location("德清县", "浙江省", "湖州市", "德清县", "330521"),
                new Forecast("2026-10-27", "2", "小雨", "小雨", 22, 16, "东北", "1-3"),
                null, null, "amap")));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse(
                        "AIM-W2", "REQ-W2", 7, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "德清", "turn-w2");

        assertThat(sink.lastContent().assistantMessage().content())
                .contains("德清后天")
                .doesNotContain("请告诉我起点和终点");
        verify(mapWeatherAi).forecast(new WeatherForecastRequest("REQ-W2", "德清", null, 2));
        ArgumentCaptor<ExtractEndpointsRequest> extract =
                ArgumentCaptor.forClass(ExtractEndpointsRequest.class);
        verify(mapRouteAi).extract(extract.capture());
        assertThat(extract.getValue().weatherContinuation()).isTrue();
    }

    @Test
    void weatherQueryOutOfRangeExplainsSupportedDays() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                "杭州", null, -1)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-W", "REQ-W", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "下周六杭州天气", "turn-w");

        assertThat(sink.lastContent().assistantMessage().content())
                .contains("今天、明天、后天、大后天");
        verify(mapWeatherAi, never()).forecast(any());
    }

    @Test
    void weatherQueryPlaceNotFoundExplainsWithoutFailure() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                "不存在市", null, 0)));
        when(mapWeatherAi.forecast(any())).thenReturn(success(new WeatherForecastResponse(
                "PLACE_NOT_FOUND", null, null, "PLACE_NOT_FOUND",
                "没有找到「不存在市」对应的城市或区县", null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-W", "REQ-W", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "不存在市今天天气", "turn-w");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("没有找到")
                .contains("补充省份");
    }

    @Test
    void weatherQueryUpstreamFailureReturnsStableFailure() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                "杭州", null, 1)));
        when(mapWeatherAi.forecast(any()))
                .thenThrow(feignError(503, "X-Ai-Error-Code", "AI_WEATHER_UNAVAILABLE"));
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-W"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        null, "REQ-W", 7L)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "杭州明天天气", "turn-w");

        assertThat(sink.lastFailed().code()).isEqualTo("AI_WEATHER_UNAVAILABLE");
        assertThat(sink.lastFailed().message()).contains("天气服务暂时不可用");
    }

    @Test
    void weatherFollowUpReusesPreviousWeatherCityAndDisclosesIt() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W2", 6,
                        weatherReply("杭州市"), null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                null, null, 2)));
        when(mapWeatherAi.forecast(any())).thenReturn(success(new WeatherForecastResponse(
                "OK",
                new Location("杭州市", "浙江省", "杭州市", "西湖区", "330100"),
                new Forecast("2026-10-27", "2", "晴", "晴", 26, 18, "东北", "1-3"),
                null, null, "amap")));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse(
                        "AIM-W2", "REQ-W2", 7, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "后天呢", "turn-w2");

        assertThat(sink.lastContent().assistantMessage().content())
                .startsWith("按你刚才说的杭州查：")
                .contains("杭州后天");
        verify(mapWeatherAi).forecast(new WeatherForecastRequest("REQ-W2", "杭州", null, 2));
        ArgumentCaptor<ExtractEndpointsRequest> extract =
                ArgumentCaptor.forClass(ExtractEndpointsRequest.class);
        verify(mapRouteAi).extract(extract.capture());
        assertThat(extract.getValue().weatherContinuation()).isTrue();
    }

    @Test
    void explicitWeatherCityOverridesPreviousWeatherContext() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W2", 6,
                        weatherReply("杭州市"), null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                "上海", null, 1)));
        when(mapWeatherAi.forecast(any())).thenReturn(success(new WeatherForecastResponse(
                "OK",
                new Location("上海市", "上海市", "上海市", "黄浦区", "310000"),
                new Forecast("2026-10-26", "1", "多云", "多云", 25, 19, "东南风", "3级"),
                null, null, "amap")));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse(
                        "AIM-W2", "REQ-W2", 7, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "上海明天天气", "turn-w2");

        assertThat(sink.lastContent().assistantMessage().content())
                .doesNotStartWith("按你刚才说的")
                .contains("上海明天");
        verify(mapWeatherAi).forecast(new WeatherForecastRequest("REQ-W2", "上海", null, 1));
    }

    @Test
    void weatherFollowUpWithoutWeatherContextAsksForCity() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-W2", 6,
                        routeReply("{\"routeCard\":{\"routeRef\":\"route-ref\"}}"), null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "WEATHER_QUERY", null, null, null, null, null,
                null, null, 1)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-W2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse(
                        "AIM-W2", "REQ-W2", 7, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "明天天气", "turn-w2");

        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("请告诉我城市，例如：杭州明天天气。");
        verify(mapWeatherAi, never()).forecast(any());
        ArgumentCaptor<ExtractEndpointsRequest> extract =
                ArgumentCaptor.forClass(ExtractEndpointsRequest.class);
        verify(mapRouteAi).extract(extract.capture());
        assertThat(extract.getValue().weatherContinuation()).isFalse();
    }

    @Test
    void currentRouteCheckUsesLatestPersistedRouteCardAndReturnsPassedVerdict() {
        String routeRef = card().routeRef();
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-3", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "CURRENT_ROUTE_CHECK",
                "德清收费站", "湖州市德清县", "VISIT_PLACE")));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.checkCurrentRoute(any())).thenReturn(success(new CurrentRouteCheckResponse(
                "CHECKED", "PASSED", "TOLL_NAVIGATION_EVENT", "导航步骤包含到达收费站事件",
                new CurrentRouteCheckTargetView("德清收费站", "收费站", "湖州",
                        "德清县", "TOLL_STATION"), "VISIT_PLACE", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-3"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-6", "REQ-3", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "当前路线经过德清收费站吗", "turn-3");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("根据当前路线的地图信息，这条路线会实际进入或通过「德清收费站」"
                        + "（导航步骤包含到达收费站事件）。");
        verify(mapRouteAi).checkCurrentRoute(new CurrentRouteCheckRequest(
                CUSTOMER_ID, CONVERSATION_NO, routeRef, 5,
                "德清收费站", "湖州市德清县", "VISIT_PLACE", "当前路线经过德清收费站吗"));
        verify(mapRouteAi, never()).prepare(any());
    }

    @Test
    void currentRouteCheckRelationAnswersNearbyWithoutAskingForMeaning() {
        String routeRef = card().routeRef();
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-3", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "CURRENT_ROUTE_CHECK", "五常收费站", null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.checkCurrentRoute(any())).thenReturn(success(new CurrentRouteCheckResponse(
                "CHECKED", "PASSED", "ROUTE_PASSES_NEARBY_ONLY",
                "路线折线与目标地点最近约652米，但没有证据表明实际通过该地点",
                new CurrentRouteCheckTargetView("五常收费站", "收费站", "杭州",
                        "余杭区", "TOLL_STATION"), "PASS_NEARBY", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-3"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-6", "REQ-3", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "这条路线经过五常收费站吗", "turn-3");

        assertThat(sink.eventNames())
                .containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.eventNames()).doesNotContain("route-check.choices");
        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("根据当前路线的地图信息，这条路线只是从「五常收费站」附近经过，"
                        + "不能确认实际通过该收费站。具体来说，路线折线与目标地点最近约652米，"
                        + "但没有证据表明实际通过该地点。"
                        + "如果你要核查的是某个具体入口/出口，请补充入口/出口名称。");
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .contains("ROUTE_PASSES_NEARBY_ONLY")
                .contains("checkMode=PASS_NEARBY");
        verify(mapRouteAi).checkCurrentRoute(new CurrentRouteCheckRequest(
                CUSTOMER_ID, CONVERSATION_NO, routeRef, 5,
                "五常收费站", null, null, "这条路线经过五常收费站吗"));
    }

    @Test
    void currentRouteCheckWithoutRouteAsksForEndpointsBeforeGeneratingOne() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "CURRENT_ROUTE_CHECK",
                "太湖服务区", null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(new AiMessageListResponse(List.of(), false)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-2", "REQ-1", 2, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "这条路线经过太湖服务区吗", "turn-1");

        assertThat(sink.lastContent().assistantMessage().content())
                .contains("当前会话还没有可核查的路线")
                .contains("请告诉我完整的起点和终点")
                .contains("我将先生成路线，再核查是否经过「太湖服务区」");
        // 两端缺失时不能凭空算路，也不能核查不存在的路线。
        verify(mapRouteAi, never()).prepare(any());
        verify(mapRouteAi, never()).defaultRoute(any());
        verify(mapRouteAi, never()).checkCurrentRoute(any());
        // 追问落库携带服务端上下文，客户端事件不暴露内部字段。
        ArgumentCaptor<AiTurnCompleteRequest> completed = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-1"), completed.capture());
        assertThat(completed.getValue().payloadJson())
                .contains("\"checkContext\"")
                .contains("\"intent\":\"CURRENT_ROUTE_CHECK\"")
                .contains("太湖服务区");
        assertThat(sink.lastContent().assistantMessage().payload()).isNull();
    }

    @Test
    void currentRouteCheckWithoutRouteGeneratesDefaultRouteThenChecksIt() {
        String routeRef = card().routeRef();
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-3", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清高速路口", "西溪湿地", null, null, "CURRENT_ROUTE_CHECK",
                "太湖服务区", null, "PASS_NEARBY")));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(new AiMessageListResponse(List.of(), false)));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_ROUTE", null, null, Instant.now().plusSeconds(300))));
        when(mapRouteAi.defaultRoute(any())).thenReturn(success(card()));
        when(mapRouteAi.checkCurrentRoute(any())).thenReturn(success(new CurrentRouteCheckResponse(
                "CHECKED", "PASSED", "ROUTE_PASSES_NEARBY", "路线折线与目标地点最近约452米",
                new CurrentRouteCheckTargetView("太湖服务区", "服务区", "湖州", "吴兴区", "SERVICE_AREA"),
                "PASS_NEARBY", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-3"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-6", "REQ-3", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "这条路线经过太湖服务区吗", "turn-3");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("这是本轮新生成的默认路线。")
                .contains("只是从「太湖服务区」附近经过")
                .contains("不能确认实际通过该服务区");
        Map<String, Object> payload = sink.lastContent().assistantMessage().payload();
        assertThat(payload).containsOnlyKeys("routeCard", "routeCheck");
        assertThat(payload.toString()).contains("polyline", routeRef);
        verify(mapRouteAi).prepare(new RoutePreparationRequest(CUSTOMER_ID, CONVERSATION_NO,
                "REQ-3", 5, "德清高速路口", null, "西溪湿地", null, "CURRENT_ROUTE_CHECK",
                "这条路线经过太湖服务区吗"));
        verify(mapRouteAi).defaultRoute(new DefaultRouteRequest(CUSTOMER_ID, CONVERSATION_NO, 5));
        verify(mapRouteAi).checkCurrentRoute(new CurrentRouteCheckRequest(CUSTOMER_ID,
                CONVERSATION_NO, routeRef, 5, "太湖服务区", null, "PASS_NEARBY",
                "这条路线经过太湖服务区吗"));
        // 持久化载荷只保留摘要，折线仍只存在于本轮 SSE 与地图短时缓存。
        ArgumentCaptor<AiTurnCompleteRequest> completed = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-3"), completed.capture());
        assertThat(completed.getValue().messageType()).isEqualTo("ROUTE_CARD");
        assertThat(completed.getValue().payloadJson())
                .contains(routeRef)
                .contains("routeCheck")
                .doesNotContain("polyline")
                .doesNotContain("checkContext");
    }

    @Test
    void routeExistsQueryAnswersFoundWithoutShowingRouteCard() {
        String detail = "途经五常收费站的路线";
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 6, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清高速路口", "西溪湿地", null, null, "ROUTE_EXISTS",
                "五常收费站", null, null)));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_ROUTE", null, null, Instant.now().plusSeconds(300))));
        when(mapRouteAi.viaRoute(any())).thenReturn(success(new ViaRouteResponse(
                "ROUTE_READY", 1, detail, viaCard(), null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "有没有经过五常收费站的路线", "turn-4");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("找到一条从当前起点到终点、经过「五常收费站」的路线")
                .contains(detail);
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .contains("routeExists")
                .contains("FOUND")
                .doesNotContain("polyline")
                .doesNotContain("routeRef");
        verify(mapRouteAi).viaRoute(new ViaRouteRequest(CUSTOMER_ID, CONVERSATION_NO,
                null, 6, 6, "五常收费站", null, "有没有经过五常收费站的路线"));
        // 终态结论不再需要续接上下文，落库载荷与客户端载荷一致。
        ArgumentCaptor<AiTurnCompleteRequest> completed = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-4"), completed.capture());
        assertThat(completed.getValue().payloadJson())
                .contains("routeExists")
                .doesNotContain("checkContext");
    }

    @Test
    void routeExistsQueryWithoutEndpointsUsesLatestPersistedRoute() {
        String routeRef = card().routeRef();
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 6, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "ROUTE_EXISTS", "五常收费站", null, null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.viaRoute(any())).thenReturn(success(new ViaRouteResponse(
                "ROUTE_READY", 1, "已验证经过五常收费站", viaCard(), null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "有没有经过五常收费站的路线？", "turn-4");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content()).contains("找到一条", "五常收费站");
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .contains("FOUND").doesNotContain("routeRef");
        verify(mapRouteAi).viaRoute(new ViaRouteRequest(CUSTOMER_ID, CONVERSATION_NO,
                routeRef, 0, 6, "五常收费站", null, "有没有经过五常收费站的路线？"));
        verify(mapRouteAi, never()).prepare(any());
    }

    @Test
    void routeExistsQueryWithExpiredPersistedRouteAsksForEndpoints() {
        String routeRef = card().routeRef();
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 6, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "ROUTE_EXISTS", "五常收费站", null, null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.viaRoute(any())).thenReturn(success(new ViaRouteResponse(
                "CONTEXT_UNAVAILABLE", 0, null, null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "有没有经过五常收费站的路线？", "turn-4");

        assertThat(sink.lastContent().assistantMessage().content())
                .contains("之前的路线已失效", "五常收费站", "完整的起点和终点");
        ArgumentCaptor<AiTurnCompleteRequest> completed = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-4"), completed.capture());
        assertThat(completed.getValue().payloadJson())
                .contains("\"previousRouteExpired\":true")
                .contains("五常收费站");
        verify(mapRouteAi).viaRoute(new ViaRouteRequest(CUSTOMER_ID, CONVERSATION_NO,
                routeRef, 0, 6, "五常收费站", null, "有没有经过五常收费站的路线？"));
        verify(mapRouteAi, never()).prepare(any());
    }

    @Test
    void routeExistsQueryWithoutPersistedRouteAsksForEndpoints() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 6, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "ROUTE_EXISTS", "五常收费站", null, null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(new AiMessageListResponse(List.of(), false)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "有没有经过五常收费站的路线？", "turn-4");

        assertThat(sink.lastContent().assistantMessage().content()).contains("完整的起点和终点");
        verify(mapRouteAi, never()).viaRoute(any());
        verify(mapRouteAi, never()).prepare(any());
    }

    @Test
    void routeExistsQueryWithOnlyOneExplicitEndpointDoesNotReuseOldRoute() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 6, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清高速路口", null, null, null, "ROUTE_EXISTS", "五常收费站", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO,
                "从德清高速路口有没有经过五常收费站的路线？", "turn-4");

        assertThat(sink.lastContent().assistantMessage().content()).contains("完整的起点和终点");
        verify(passengerAi, never()).listMessages(eq(CONVERSATION_NO), eq(CUSTOMER_ID), eq(50), isNull());
        verify(mapRouteAi, never()).viaRoute(any());
        verify(mapRouteAi, never()).prepare(any());
    }

    @Test
    void routeExistsQueryReportsNotFoundInQueryScope() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 6, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清高速路口", "西溪湿地", null, null, "ROUTE_EXISTS",
                "五常收费站", null, null)));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_ROUTE", null, null, Instant.now().plusSeconds(300))));
        when(mapRouteAi.viaRoute(any())).thenReturn(success(new ViaRouteResponse(
                "ROUTE_NOT_FOUND", 1, "高德未返回经过该地点的路线", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "有没有经过五常收费站的路线", "turn-4");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("本次查询没有找到经过「五常收费站」的已验证路线")
                .contains("可以补充城市或换一个地点再试");
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .contains("routeExists")
                .contains("NOT_FOUND_IN_QUERY");
    }

    @Test
    void routeExistsQueryPlaceChoiceContinuesViaSelection() throws Exception {
        String taskNo = "AIT-00000000-0000-4000-8000-000000000005";
        String choiceId = "PC-00000000-0000-4000-8000-000000000005";
        Instant expiresAt = Instant.now().plusSeconds(300);
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 6, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                "德清高速路口", "西溪湿地", null, null, "ROUTE_EXISTS",
                "五常收费站", null, null)));
        when(mapRouteAi.prepare(any())).thenReturn(success(new RoutePreparationResponse(
                "READY_FOR_ROUTE", null, null, Instant.now().plusSeconds(300))));
        when(mapRouteAi.viaRoute(any())).thenReturn(success(new ViaRouteResponse(
                "NEEDS_PLACE_CHOICE", 6, null, null, new ViaRoutePlaceChoicesView(taskNo, "WAYPOINT",
                List.of(new ViaRoutePlaceChoiceView(choiceId, "五常收费站", "收费站",
                        "杭州", "余杭区", "TOLL_STATION", expiresAt)), expiresAt))));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "有没有经过五常收费站的路线", "turn-4");

        assertThat(sink.eventNames()).containsExactly("turn.started", "place.choices", "turn.completed");
        ArgumentCaptor<AiTurnCompleteRequest> asked = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-4"), asked.capture());
        assertThat(asked.getValue().payloadJson())
                .contains("\"flow\":\"ROUTE_EXISTS\"")
                .contains("\"checkContext\"")
                .contains("五常收费站");
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .doesNotContain("checkContext")
                .doesNotContain("routeRef");

        AssistantMessageView latest = new AssistantMessageView("AIM-8", "REQ-4", "ASSISTANT",
                "PLACE_CHOICES", "请确认具体地点", asked.getValue().payloadJson(), 8,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(placeChoiceHistory(latest)));
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-5", 7, latest, null)));
        when(mapRouteAi.selectViaRoutePlace(any())).thenReturn(success(new ViaRouteResponse(
                "ROUTE_READY", 6, "途经五常收费站的路线", viaCard(), null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-5"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-10", "REQ-5", 10, LocalDateTime.now())));
        sink = new RecordingSink();

        service.selectPlaceChoice(sink, CUSTOMER_ID, CONVERSATION_NO,
                taskNo, choiceId, 6, "五常收费站", "杭州", "turn-5");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("找到一条从当前起点到终点、经过「五常收费站」的路线");
        verify(mapRouteAi).selectViaRoutePlace(new ViaRoutePlaceChoiceRequest(CUSTOMER_ID,
                CONVERSATION_NO, 6, 6, 7, taskNo, choiceId));
        verify(mapRouteAi, never()).selectCurrentRouteCheckPlace(any());
    }

    @Test
    void replayedRouteCardRestoresStoredRouteCheckAlongsidePolyline() {
        RouteCardDto source = card();
        RouteCardDto current = new RouteCardDto(source.routeRef(), source.origin(), source.destination(),
                source.via(), source.route(), Instant.now().minusSeconds(10), Instant.now().plusSeconds(240));
        String routeRef = source.routeRef();
        String stored = "{\"routeCard\":{\"routeRef\":\"" + routeRef
                + "\",\"origin\":{\"name\":\"德清高速路口\"},"
                + "\"destination\":{\"name\":\"西溪湿地\"},"
                + "\"route\":{\"distanceMeters\":45600,\"durationSeconds\":3300}},"
                + "\"routeCheck\":{\"routeRef\":\"" + routeRef + "\",\"status\":\"CHECKED\","
                + "\"verdict\":\"PASSED\",\"checkMode\":\"PASS_NEARBY\","
                + "\"target\":{\"name\":\"太湖服务区\"}}}";
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-3", 5, null, routeReply(stored))));
        when(mapRouteAi.replay(new RouteReplayRequest(CUSTOMER_ID, CONVERSATION_NO, routeRef)))
                .thenReturn(success(new RouteReplayResponse(current)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "这条路线经过太湖服务区吗", "turn-3");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        Map<String, Object> payload = sink.lastContent().assistantMessage().payload();
        assertThat(payload).containsOnlyKeys("routeCard", "routeCheck");
        assertThat(payload.toString())
                .contains("polyline", "PASSED", "太湖服务区")
                .doesNotContain("expired=true");
    }

    @Test
    void viaRoutePlanUsesLatestPersistedRouteAndPersistsNewViaSummary() {
        String routeRef = card().routeRef();
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-7", 13, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "VIA_ROUTE_PLAN",
                "五常收费站", null, null, null, null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.viaRoute(any())).thenReturn(success(new ViaRouteResponse(
                "ROUTE_READY", 1, null, viaCard(), null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-7"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-14", "REQ-7", 14,
                        LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "改走五常收费站", "turn-7");

        assertThat(sink.eventNames()).containsExactly("turn.started", "route.card", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content()).contains("途经五常收费站");
        verify(mapRouteAi).viaRoute(new ViaRouteRequest(CUSTOMER_ID, CONVERSATION_NO,
                routeRef, 0, 13, "五常收费站", null, "改走五常收费站"));
        ArgumentCaptor<AiTurnCompleteRequest> completed = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-7"), completed.capture());
        assertThat(completed.getValue().payloadJson())
                .contains("\"via\":{\"name\":\"五常收费站\"}")
                .doesNotContain("polyline");
    }

    @Test
    void currentRouteCheckPlaceChoiceContinuesTheSameCheck() {
        String routeRef = card().routeRef();
        String taskNo = "AIT-00000000-0000-4000-8000-000000000002";
        String choiceId = "PC-00000000-0000-4000-8000-000000000002";
        Instant expiresAt = Instant.now().plusSeconds(300);
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-3", 5, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "CURRENT_ROUTE_CHECK",
                "德清收费站", null, "UNSPECIFIED")));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.checkCurrentRoute(any())).thenReturn(success(new CurrentRouteCheckResponse(
                "NEEDS_PLACE_CHOICE", null, null, null, null,
                new CurrentRouteCheckPlaceChoicesView(taskNo, "WAYPOINT", List.of(
                        new CurrentRouteCheckPlaceChoiceView(choiceId, "德清收费站", "收费站",
                                "湖州", "德清县", "TOLL_STATION", expiresAt)), expiresAt))));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-3"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-6", "REQ-3", 6, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "当前路线经过德清收费站吗", "turn-3");

        ArgumentCaptor<AiTurnCompleteRequest> first = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-3"), first.capture());
        assertThat(first.getValue().messageType()).isEqualTo("PLACE_CHOICES");
        assertThat(first.getValue().content()).contains("德清收费站");
        // 落库载荷携带服务端续接上下文，客户端事件只看到公开选项。
        assertThat(first.getValue().payloadJson())
                .contains("\"flow\":\"CURRENT_ROUTE_CHECK\"")
                .contains(choiceId)
                .contains("\"checkContext\"")
                .contains(routeRef);
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .doesNotContain("routeRef")
                .doesNotContain("checkContext");

        AssistantMessageView latest = new AssistantMessageView("AIM-6", "REQ-3", "ASSISTANT",
                "PLACE_CHOICES", "请选择核查地点", first.getValue().payloadJson(), 6,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(placeChoiceHistory(latest)));
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 7, latest, null)));
        when(mapRouteAi.selectCurrentRouteCheckPlace(any())).thenReturn(success(
                new CurrentRouteCheckResponse("CHECKED", "NOT_PASSED", "NO_MATCHING_EVENT",
                        "导航步骤中没有匹配事件",
                        new CurrentRouteCheckTargetView("德清收费站", "收费站", "湖州",
                                "德清县", "TOLL_STATION"), "VISIT_PLACE", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-4"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-8", "REQ-4", 8, LocalDateTime.now())));
        sink = new RecordingSink();

        service.selectPlaceChoice(sink, CUSTOMER_ID, CONVERSATION_NO,
                taskNo, choiceId, 5, "伪造的机场", "北京", "turn-4");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .isEqualTo("根据当前路线的地图信息，这条路线不会实际进入或通过「德清收费站」"
                        + "（导航步骤中没有匹配事件）。");
        ArgumentCaptor<AiTurnBeginRequest> beginRequests = ArgumentCaptor.forClass(AiTurnBeginRequest.class);
        verify(passengerAi, times(2)).begin(eq(CONVERSATION_NO), beginRequests.capture());
        assertThat(beginRequests.getAllValues().getLast().content())
                .isEqualTo("已选择地点：德清收费站（湖州）");
        verify(mapRouteAi).selectCurrentRouteCheckPlace(new CurrentRouteCheckPlaceChoiceRequest(
                CUSTOMER_ID, CONVERSATION_NO, routeRef, 5, 7, taskNo, choiceId));
        verify(mapRouteAi, never()).selectPlace(any());
    }

    @Test
    void ambiguousPassageMeaningIsClarifiedAndResumesTheSameRouteCheck() {
        String routeRef = card().routeRef();
        String taskNo = "RCT-00000000-0000-4000-8000-000000000003";
        String label = "沿路线经过该地点附近";
        Instant expiresAt = Instant.now().plusSeconds(300);
        CurrentRouteCheckTargetView target = new CurrentRouteCheckTargetView(
                "南浔西收费站(S47湖杭高速出口)", "收费站", "湖州", "南浔区", "TOLL_STATION");
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-5", 9, null, null)));
        when(mapRouteAi.extract(any())).thenReturn(success(new ExtractEndpointsResponse(
                null, null, null, null, "CURRENT_ROUTE_CHECK",
                "南浔西高速出口", null, "UNSPECIFIED")));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.checkCurrentRoute(any())).thenReturn(success(new CurrentRouteCheckResponse(
                "NEEDS_CHECK_MODE_CHOICE", null, null, null, target, null, null,
                new CurrentRouteCheckModeChoicesView(taskNo, target, List.of(
                        new CurrentRouteCheckModeChoiceView("PASS_NEARBY", label),
                        new CurrentRouteCheckModeChoiceView("USE_EXIT", "从该出口驶出高速")), expiresAt))));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-5"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-10", "REQ-5", 10, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO,
                "这条路线经过南浔西高速出口吗", "turn-5");

        ArgumentCaptor<AiTurnCompleteRequest> question = ArgumentCaptor.forClass(AiTurnCompleteRequest.class);
        verify(passengerAi).complete(eq(CONVERSATION_NO), eq("REQ-5"), question.capture());
        assertThat(question.getValue().messageType()).isEqualTo("ROUTE_CHECK_CHOICES");
        // 落库载荷携带服务端续接上下文，客户端事件只看到公开选项。
        assertThat(question.getValue().payloadJson())
                .contains(taskNo).contains(label).contains("\"checkContext\"").contains(routeRef);
        assertThat(sink.lastContent().assistantMessage().payload().toString())
                .doesNotContain("routeRef")
                .doesNotContain("checkContext");
        assertThat(sink.eventNames()).containsExactly(
                "turn.started", "route-check.choices", "turn.completed");

        AssistantMessageView latest = new AssistantMessageView("AIM-10", "REQ-5", "ASSISTANT",
                "ROUTE_CHECK_CHOICES", "请选择核查方式", question.getValue().payloadJson(), 10,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-6", 11, latest, null)));
        when(mapRouteAi.selectCurrentRouteCheckMode(any())).thenReturn(success(
                new CurrentRouteCheckResponse("CHECKED", "PASSED", "ROUTE_PASSES_NEARBY",
                        "路线折线与目标地点最近约452米", target,
                        "PASS_NEARBY", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-6"), any()))
                .thenReturn(success(new AiTurnCompleteResponse("AIM-12", "REQ-6", 12, LocalDateTime.now())));
        sink = new RecordingSink();

        service.selectCurrentRouteCheckMode(sink, CUSTOMER_ID, CONVERSATION_NO,
                taskNo, "PASS_NEARBY", 9, label, "turn-6");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("只是从「南浔西收费站(S47湖杭高速出口)」附近经过")
                .contains("不能确认实际通过该收费站")
                .contains("最近约452米");
        verify(mapRouteAi).selectCurrentRouteCheckMode(new CurrentRouteCheckModeChoiceRequest(
                CUSTOMER_ID, CONVERSATION_NO, routeRef, 9, 11, taskNo, "PASS_NEARBY"));
    }

    @Test
    void routeSemanticConflictExplainsWhyEntranceCannotBeUsedAgain() throws Exception {
        String routeRef = card().routeRef();
        String taskNo = "RCT-00000000-0000-4000-8000-000000000004";
        String label = "从该入口驶入高速";
        CurrentRouteCheckTargetView target = new CurrentRouteCheckTargetView(
                "南浔西收费站(S47湖杭高速入口)", "收费站", "湖州", "南浔区", "TOLL_STATION");
        String payload = objectMapper.writeValueAsString(Map.of("routeCheckChoices", Map.of(
                "taskNo", taskNo,
                "target", target,
                "choices", List.of(
                        new CurrentRouteCheckModeChoiceView("PASS_NEARBY", "沿路线经过该地点附近"),
                        new CurrentRouteCheckModeChoiceView("ENTER_HIGHWAY", label)),
                "requestVersion", 9)));
        AssistantMessageView latest = new AssistantMessageView("AIM-12", "REQ-6", "ASSISTANT",
                "ROUTE_CHECK_CHOICES", "请选择核查方式", payload, 10,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-7", 11, latest, null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.selectCurrentRouteCheckMode(any())).thenReturn(success(
                new CurrentRouteCheckResponse("CHECKED", "CONFLICT",
                        "ALREADY_ON_HIGHWAY_BEFORE_TARGET",
                        "路线在“德清互通”已经驶入高速，接近目标地点时仍行驶在“S47湖杭高速”上，"
                                + "因此不会再从该入口驶入高速",
                        target, "ENTER_HIGHWAY", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-7"), any()))
                .thenReturn(success(new AiTurnCompleteResponse(
                        "AIM-14", "REQ-7", 12, LocalDateTime.now())));

        service.selectCurrentRouteCheckMode(sink, CUSTOMER_ID, CONVERSATION_NO,
                taskNo, "ENTER_HIGHWAY", 9, label, "turn-7");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("德清互通")
                .contains("不会再从该入口驶入高速")
                .contains("是否经过“南浔西收费站(S47湖杭高速入口)”附近")
                .contains("请选择对应的出口地点")
                .doesNotContain("暂时无法确认");
        verify(mapRouteAi).selectCurrentRouteCheckMode(new CurrentRouteCheckModeChoiceRequest(
                CUSTOMER_ID, CONVERSATION_NO, routeRef, 9, 11, taskNo, "ENTER_HIGHWAY"));
    }

    @Test
    void tollDirectionConflictDoesNotTellPassengerThatAnExitRouteUsesTheEntrance() throws Exception {
        String routeRef = card().routeRef();
        String taskNo = "RCT-00000000-0000-4000-8000-000000000005";
        String label = "从该入口驶入高速";
        CurrentRouteCheckTargetView target = new CurrentRouteCheckTargetView(
                "五常收费站(G25长深高速入口北向)", "收费站", "杭州", "余杭区", "TOLL_STATION");
        String payload = objectMapper.writeValueAsString(Map.of("routeCheckChoices", Map.of(
                "taskNo", taskNo,
                "target", target,
                "choices", List.of(
                        new CurrentRouteCheckModeChoiceView("PASS_NEARBY", "沿路线经过该地点附近"),
                        new CurrentRouteCheckModeChoiceView("ENTER_HIGHWAY", label)),
                "requestVersion", 9)));
        AssistantMessageView latest = new AssistantMessageView("AIM-14", "REQ-7", "ASSISTANT",
                "ROUTE_CHECK_CHOICES", "请选择核查方式", payload, 12,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-8", 13, latest, null)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(routeHistory(routeRef)));
        when(mapRouteAi.selectCurrentRouteCheckMode(any())).thenReturn(success(
                new CurrentRouteCheckResponse("CHECKED", "CONFLICT",
                        "TOLL_ACTION_DIRECTION_CONFLICT",
                        "路线在到达目标收费站前仍行驶在高速上，收费站后离开高速，"
                                + "属于驶出高速，不是从该入口驶入",
                        target, "ENTER_HIGHWAY", null, null)));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-8"), any()))
                .thenReturn(success(new AiTurnCompleteResponse(
                        "AIM-16", "REQ-8", 14, LocalDateTime.now())));

        service.selectCurrentRouteCheckMode(sink, CUSTOMER_ID, CONVERSATION_NO,
                taskNo, "ENTER_HIGHWAY", 9, label, "turn-8");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content())
                .contains("当前提问与所选地点或路线状态不一致")
                .contains("属于驶出高速，不是从该入口驶入")
                .doesNotContain("会从「五常收费站");
        verify(mapRouteAi).selectCurrentRouteCheckMode(new CurrentRouteCheckModeChoiceRequest(
                CUSTOMER_ID, CONVERSATION_NO, routeRef, 9, 13, taskNo, "ENTER_HIGHWAY"));
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
    void lostCompleteResponseDoesNotTurnStoredSuccessIntoFailure() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-1", 1, null, null)));
        when(mapRouteAi.extract(any()))
                .thenThrow(feignError(503, "X-Ai-Error-Code", "AI_PROVIDER_UNAVAILABLE"));
        AssistantMessageView completed = new AssistantMessageView(
                "AIM-2", "REQ-1", "ASSISTANT", "TEXT", "已完成处理", null,
                2, LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-1"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        "AIM-2", "REQ-1", 2, completed)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "从A到B", "turn-1");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content()).isEqualTo("已完成处理");
        assertThat(sink.lastFailed()).isNull();
    }

    @Test
    void expiredRouteContextCompletesWithFreshEndpointQuestion() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONFIRMED", null)));
        when(mapRouteAi.defaultRoute(any()))
                .thenThrow(feignError(409, "X-Ai-Error-Code", "AI_ROUTE_CONTEXT_UNAVAILABLE"));
        when(passengerAi.complete(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new AiTurnCompleteResponse(
                        "AIM-4", "REQ-2", 4, LocalDateTime.now())));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        assertThat(sink.lastContent().assistantMessage().content()).contains("路线条件已过期");
        verify(passengerAi, never()).fail(eq(CONVERSATION_NO), eq("REQ-2"), any());
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
    void unverifiedTollEndpointExplainsEvidenceFailureInsteadOfGenericOutage() {
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-2", 2, confirmationQuestion(), null)));
        when(mapRouteAi.confirm(any())).thenReturn(success(new RouteConfirmResponse("CONFIRMED", null)));
        when(mapRouteAi.defaultRoute(any()))
                .thenThrow(feignError(422, "X-Ai-Error-Code", "AI_ROUTE_UNVERIFIABLE"));
        when(passengerAi.fail(eq(CONVERSATION_NO), eq("REQ-2"), any()))
                .thenReturn(success(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse(
                        "AIM-4", "REQ-2", 4)));

        service.execute(sink, CUSTOMER_ID, CONVERSATION_NO, "对", "turn-2");

        assertThat(sink.eventNames()).containsExactly("turn.started", "turn.failed");
        assertThat(sink.lastFailed().code()).isEqualTo("AI_ROUTE_UNVERIFIABLE");
        assertThat(sink.lastFailed().message()).contains("已找到驾车路线", "无法确认", "收费站");
        verify(passengerAi, never()).complete(eq(CONVERSATION_NO), eq("REQ-2"), any());
        ArgumentCaptor<AiTurnFailRequest> failure = ArgumentCaptor.forClass(AiTurnFailRequest.class);
        verify(passengerAi).fail(eq(CONVERSATION_NO), eq("REQ-2"), failure.capture());
        assertThat(failure.getValue().failureCode()).isEqualTo("AI_ROUTE_UNVERIFIABLE");
        assertThat(failure.getValue().failureMessage()).isEqualTo(sink.lastFailed().message());
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

    private static AssistantMessageView weatherReply(String city) {
        String payload = "{\"weather\":{\"location\":{\"name\":\"" + city + "\",\"city\":\""
                + city + "\",\"district\":\"西湖区\",\"adcode\":\"330100\"},"
                + "\"date\":\"2026-10-26\"}}";
        return new AssistantMessageView("AIM-W", "REQ-W", "ASSISTANT", "TEXT",
                "杭州明天天气", payload, 5, LocalDateTime.now(), "COMPLETED", null, null);
    }

    private static AssistantMessageView weatherAskReply(Integer dayOffset) {
        String payload = dayOffset == null
                ? "{\"weatherContext\":{}}"
                : "{\"weatherContext\":{\"dayOffset\":" + dayOffset + "}}";
        return new AssistantMessageView("AIM-W", "REQ-W", "ASSISTANT", "TEXT",
                "请告诉我城市", payload, 5, LocalDateTime.now(), "COMPLETED", null, null);
    }

    private static AiMessageListResponse routeHistory(String routeRef) {
        String payload = "{\"routeCard\":{\"routeRef\":\"" + routeRef + "\"}}";
        return new AiMessageListResponse(List.of(new MessageView(
                "AIM-4", "REQ-2", null, "ASSISTANT", "ROUTE_CARD",
                "已为您规划路线", payload, 4, "COMPLETED", LocalDateTime.now())), false);
    }

    private static AiMessageListResponse placeChoiceHistory(AssistantMessageView choice) {
        return new AiMessageListResponse(List.of(new MessageView(
                choice.messageNo(), choice.requestNo(), null, choice.role(), choice.messageType(),
                choice.content(), choice.payloadJson(), choice.sequenceNo(), choice.status(),
                choice.createdAt())), false);
    }

    @Test
    void replayedPlaceChoiceUsesPersistedLabelEvenWhenClientChangesHints() {
        String taskNo = "AIT-replay";
        String choiceId = "PC-replay";
        String payload = "{\"placeChoices\":{\"taskNo\":\"AIT-replay\",\"requestVersion\":3,"
                + "\"choices\":[{\"placeChoiceId\":\"PC-replay\",\"name\":\"德清收费站\",\"city\":\"湖州\"}]}}";
        AssistantMessageView previous = new AssistantMessageView("AIM-3", "REQ-3", "ASSISTANT",
                "PLACE_CHOICES", "请选地点", payload, 4, LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(new AiMessageListResponse(List.of(new MessageView(
                        "AIM-9", "REQ-8", null, "ASSISTANT", "TEXT", "后续回复", null,
                        9, "COMPLETED", LocalDateTime.now())), true)));
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, 9L))
                .thenReturn(success(placeChoiceHistory(previous)));
        AssistantMessageView reply = new AssistantMessageView("AIM-6", "REQ-4", "ASSISTANT",
                "TEXT", "已确认", null, 6, LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.begin(eq(CONVERSATION_NO), any()))
                .thenReturn(success(new AiTurnBeginResponse("REQ-4", 5, previous, reply)));

        service.selectPlaceChoice(sink, CUSTOMER_ID, CONVERSATION_NO,
                taskNo, choiceId, 3, "伪造的机场", "北京", "same-key");

        ArgumentCaptor<AiTurnBeginRequest> request = ArgumentCaptor.forClass(AiTurnBeginRequest.class);
        verify(passengerAi).begin(eq(CONVERSATION_NO), request.capture());
        assertThat(request.getValue().content()).isEqualTo("已选择地点：德清收费站（湖州）");
        assertThat(sink.eventNames()).containsExactly("turn.started", "answer.completed", "turn.completed");
        verify(mapRouteAi, never()).selectPlace(any());
    }

    @Test
    void unknownPlaceChoiceDoesNotPersistClientLabel() {
        AssistantMessageView previous = new AssistantMessageView("AIM-3", "REQ-3", "ASSISTANT",
                "PLACE_CHOICES", "请选地点", "{\"placeChoices\":{\"taskNo\":\"AIT-old\","
                        + "\"requestVersion\":3,\"choices\":[]}}", 4,
                LocalDateTime.now(), "COMPLETED", null, null);
        when(passengerAi.listMessages(CONVERSATION_NO, CUSTOMER_ID, 50, null))
                .thenReturn(success(placeChoiceHistory(previous)));

        service.selectPlaceChoice(sink, CUSTOMER_ID, CONVERSATION_NO,
                "AIT-other", "PC-forged", 3, "伪造的机场", "北京", "new-key");

        assertThat(sink.eventNames()).containsExactly("turn.failed");
        assertThat(sink.lastFailed.code()).isEqualTo("AI_PLACE_CHOICE_INVALID");
        verify(passengerAi, never()).begin(any(), any());
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

    private static RouteCardDto viaCard() {
        RouteCardDto base = card();
        return new RouteCardDto(base.routeRef(), base.origin(), base.destination(),
                new PlaceView("五常收费站"), base.route(), base.generatedAt(), base.expiresAt());
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
