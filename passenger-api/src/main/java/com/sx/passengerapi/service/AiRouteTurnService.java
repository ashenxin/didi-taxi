package com.sx.passengerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.MapRouteAiClient;
import com.sx.passengerapi.client.MapWeatherAiClient;
import com.sx.passengerapi.client.PassengerAiConversationClient;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationDeleteResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationListResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiRequestStatusResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AssistantMessageView;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.MessageView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckPlaceChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckModeChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.CurrentRouteCheckResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.DefaultRouteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.EndpointFeedbackView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteCardDto;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePlaceChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PartialEndpointPrepareRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PartialEndpointCompleteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRoutePlaceChoiceRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRouteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ViaRouteResponse;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastRequest;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ai.AiSseEvents.AssistantMessageEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.ContentEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnCompletedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnFailedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnStartedEvent;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 客服轮次编排：确认两端、生成默认或指定途经点路线卡、核查当前路线，以及天气 skill 的单轮问答。
 *
 * 地图地点、路线与分钟数只来自 map-service 确定性能力，模型只负责提取地点名称；
 * 本类按结构化状态决定分支，不依赖模型文本作为业务权威。事件顺序遵循 API 契约：
 * turn.started → answer.completed / place.choices / route.card → turn.completed，失败发送 turn.failed。
 */
@Service
public class AiRouteTurnService {

    private static final Logger log = LoggerFactory.getLogger(AiRouteTurnService.class);

    static final String CONFIRMATION_MARKER = "endpointConfirmation";
    static final String CONFIRMATION_REPLY = "对";
    static final String CONFIRMED = "CONFIRMED";
    static final String NOT_ACCEPTED = "NOT_ACCEPTED";
    static final String READY_FOR_ROUTE = "READY_FOR_ROUTE";
    static final String WAITING_FOR_OTHER_ENDPOINT = "WAITING_FOR_OTHER_ENDPOINT";
    static final String NEEDS_PLACE_CHOICE = "NEEDS_PLACE_CHOICE";
    static final String NEEDS_CHECK_MODE_CHOICE = "NEEDS_CHECK_MODE_CHOICE";
    static final String UNIQUE = "UNIQUE";
    static final String CURRENT_ROUTE_CHECK = "CURRENT_ROUTE_CHECK";
    static final String VIA_ROUTE_PLAN = "VIA_ROUTE_PLAN";
    static final String ROUTE_EXISTS = "ROUTE_EXISTS";
    static final String WEATHER_QUERY = "WEATHER_QUERY";

    private static final String DEFAULT_PLACE_CHOICE_FLOW = "ROUTE_PREPARATION";
    private static final String PARTIAL_ENDPOINT_FLOW = "PARTIAL_ENDPOINT";
    private static final String CHECK_CONTEXT_FIELD = "checkContext";
    private static final String WEATHER_CONTEXT_FIELD = "weatherContext";
    private static final int MESSAGE_PAGE_SIZE = 50;

    private static final String ASK_FOR_ENDPOINTS =
            "请告诉我起点和终点，例如：从德清高速路口到西溪湿地。";
    private static final String CONFIRMATION_NOT_ACCEPTED =
            "请回复「对」确认，或直接告诉我需要修改的地点。";
    private static final String CONFIRMATION_EXPIRED =
            "之前的起终点信息已失效，请重新告诉我完整的起点和终点。";
    private static final String ASK_FOR_CHECK_PLACE =
            "请告诉我要核查的收费站、服务区或加油站名称。";
    private static final String ASK_FOR_VIA_PLACE =
            "请告诉我新路线必须经过的收费站、服务区或加油站名称。";
    private static final String CURRENT_ROUTE_UNAVAILABLE =
            "当前会话没有可核查的有效路线，请先规划一条路线。";
    private static final String ASK_FOR_ROUTE_EXISTS_PLACE =
            "请告诉我要查询的收费站、服务区或加油站名称。";
    private static final String NEW_DEFAULT_ROUTE_PREFIX = "这是本轮新生成的默认路线。";
    private static final String ASK_FOR_WEATHER =
            "请告诉我城市和日期，例如：杭州明天天气。";
    private static final String WEATHER_DATE_RANGE =
            "目前只能查今天、明天、后天、大后天这四天的预报，请换个日期。";
    private static final Set<String> CHECK_MODES =
            Set.of("PASS_NEARBY", "USE_EXIT", "ENTER_HIGHWAY", "VISIT_PLACE");

    private final PassengerAiConversationClient passengerAi;
    private final MapRouteAiClient mapRouteAi;
    private final MapWeatherAiClient mapWeatherAi;
    private final ObjectMapper objectMapper;

    public AiRouteTurnService(PassengerAiConversationClient passengerAi,
                              MapRouteAiClient mapRouteAi,
                              MapWeatherAiClient mapWeatherAi,
                              ObjectMapper objectMapper) {
        this.passengerAi = passengerAi;
        this.mapRouteAi = mapRouteAi;
        this.mapWeatherAi = mapWeatherAi;
        this.objectMapper = objectMapper;
    }

    /** 创建 AI 客服会话；幂等键重放由 passenger-service 保证。 */
    public AiConversationCreateResponse createConversation(long customerId, String idempotencyKey) {
        return unwrap(passengerAi.create(new AiConversationCreateRequest(customerId, idempotencyKey)), "create");
    }

    /** 分页读取历史消息；归属校验在下游，跨乘客访问按会话不存在处理。 */
    public AiMessageListResponse listMessages(long customerId, String conversationNo,
                                              Integer limit, Long beforeSequence) {
        AiMessageListResponse page = unwrap(passengerAi.listMessages(
                conversationNo, customerId, limit, beforeSequence), "listMessages");
        if (page.messages() == null) {
            return page;
        }
        return new AiMessageListResponse(page.messages().stream()
                .map(this::withoutInternalContext).toList(), page.hasMore());
    }

    public AiConversationListResponse listConversations(long customerId, Integer limit,
                                                        String beforeConversationNo) {
        return unwrap(passengerAi.listConversations(
                customerId, limit, beforeConversationNo), "listConversations");
    }

    public AiRequestStatusResponse requestStatus(long customerId, String conversationNo,
                                                 String requestNo) {
        AiRequestStatusResponse status = unwrap(passengerAi.requestStatus(
                conversationNo, requestNo, customerId), "requestStatus");
        return new AiRequestStatusResponse(status.requestNo(), status.requestVersion(),
                status.clientMessageNo(), status.status(),
                withoutInternalContext(status.assistantReply()));
    }

    public AiConversationDeleteResponse deleteConversation(long customerId,
                                                            String conversationNo) {
        return unwrap(passengerAi.deleteConversation(
                conversationNo, customerId), "deleteConversation");
    }

    /** 执行一轮文字消息；任何异常都以 turn.failed 收尾。 */
    public void execute(AiTurnEventSink sink, long customerId, String conversationNo,
                        String content, String idempotencyKey) {
        String requestNo = null;
        try {
            AiTurnBeginResponse begin = unwrap(passengerAi.begin(conversationNo,
                    new AiTurnBeginRequest(customerId, idempotencyKey, content)), "begin");
            requestNo = begin.requestNo();
            sink.turnStarted(new TurnStartedEvent(requestNo, begin.requestVersion(), content));

            if (begin.assistantReply() != null) {
                // 幂等重放只使用已落库的最终状态；失败轮次不能改发成功内容事件。
                AssistantMessageView reply = begin.assistantReply();
                if ("FAILED".equals(reply.status())) {
                    sink.turnFailed(replayFailure(requestNo, reply));
                    return;
                }
                if (!"COMPLETED".equals(reply.status())) {
                    sink.turnFailed(new TurnFailedEvent(requestNo, "AI_INTERNAL_ERROR",
                            "服务暂时不可用，请稍后重试"));
                    return;
                }
                emitCompletedReply(sink, customerId, conversationNo, reply);
                return;
            }

            if (isConfirmationReply(content, begin)) {
                handleConfirmation(sink, customerId, conversationNo, requestNo, begin);
                sink.turnCompleted(new TurnCompletedEvent(requestNo));
                return;
            }

            handleFreshText(sink, customerId, conversationNo, requestNo,
                    begin.requestVersion(), content, begin.latestAssistantMessage());
            sink.turnCompleted(new TurnCompletedEvent(requestNo));
        } catch (Exception e) {
            TurnFailedEvent failed = classify(requestNo, e);
            log.warn("AI 客服轮次失败 requestNo={} code={} type={}",
                    requestNo, failed.code(), e.getClass().getSimpleName(), e);
            finishFailedTurn(sink, customerId, conversationNo, requestNo, failed);
        }
    }

    /** 兼容改版前已经落库的确认提问；新流程不再生成这类消息。 */
    private void handleConfirmation(AiTurnEventSink sink, long customerId, String conversationNo,
                                    String requestNo, AiTurnBeginResponse begin) {
        AssistantMessageView question = begin.latestAssistantMessage();
        ConfirmationPayload payload = parseConfirmationPayload(question.payloadJson());

        RouteConfirmResponse confirm = unwrap(mapRouteAi.confirm(new RouteConfirmRequest(
                customerId, conversationNo, question.requestNo(), payload.conditionVersion(), CONFIRMATION_REPLY)),
                "confirm");
        switch (confirm.status()) {
            case CONFIRMED -> {
                completeDefaultRoute(sink, customerId, conversationNo, requestNo,
                        payload.conditionVersion());
            }
            case NOT_ACCEPTED -> completeText(sink, customerId, conversationNo, requestNo,
                    CONFIRMATION_NOT_ACCEPTED, null);
            default -> completeText(sink, customerId, conversationNo, requestNo,
                    CONFIRMATION_EXPIRED, null);
        }
    }

    /**
     * 非确认文字：先识别业务意图，再进入当前路线核查或默认路线准备分支。
     *
     * conditionVersion 直接使用本轮用户消息的会话内序号（begin.requestVersion）：
     * 会话内单调递增，端点一旦变更，新版本使旧条件与旧路线快照自然失效，
     * 符合"乘客更改任一端时使旧路线与选项失效"的契约。
     */
    private void handleFreshText(AiTurnEventSink sink, long customerId, String conversationNo,
                                 String requestNo, long conditionVersion, String content,
                                 AssistantMessageView latestAssistantMessage) {
        PartialEndpointPayload pending = parsePartialEndpointPayload(latestAssistantMessage);
        WeatherContextResolver.Context weatherContext =
                WeatherContextResolver.from(latestAssistantMessage, objectMapper);
        ExtractEndpointsResponse extracted = unwrap(mapRouteAi.extract(
                new ExtractEndpointsRequest(content,
                        pending == null ? null : pending.missingRole(),
                        weatherContext.anyWeatherContext())), "extract");
        if (WEATHER_QUERY.equals(extracted.intent())) {
            handleWeatherQuery(sink, customerId, conversationNo, requestNo,
                    content, extracted, weatherContext);
            return;
        }
        if (CURRENT_ROUTE_CHECK.equals(extracted.intent())) {
            handleCurrentRouteCheck(sink, customerId, conversationNo, requestNo,
                    conditionVersion, content, extracted);
            return;
        }
        if (ROUTE_EXISTS.equals(extracted.intent())) {
            handleRouteExists(sink, customerId, conversationNo, requestNo,
                    conditionVersion, content, extracted);
            return;
        }
        // 上一轮只问到一半端点的核查请求：本轮补齐两端后继续原核查，而不是改判成普通规划。
        CheckContext pendingCheck = parsePendingCheckContext(latestAssistantMessage);
        if (pendingCheck != null) {
            if (hasText(extracted.originName()) && hasText(extracted.destinationName())) {
                prepareCheckWithEndpoints(sink, customerId, conversationNo, requestNo,
                        conditionVersion, content, extracted, pendingCheck);
            } else {
                askForCheckEndpoints(sink, customerId, conversationNo, requestNo, pendingCheck);
            }
            return;
        }
        // 上一轮是天气追问（例如“请告诉我城市”）：本轮只回一个城市时继续天气，
        // 不把它当成路线起点。若乘客明确给出两端或路线动作，仍按路线处理。
        if (weatherContext.pendingAsk()) {
            ExtractEndpointsResponse weatherReply =
                    weatherReplyFromPending(extracted, content, weatherContext);
            if (weatherReply != null) {
                handleWeatherQuery(sink, customerId, conversationNo, requestNo,
                        content, weatherReply, weatherContext);
                return;
            }
        }
        boolean hasOrigin = hasText(extracted.originName());
        boolean hasDestination = hasText(extracted.destinationName());
        if (pending != null && !(hasOrigin && hasDestination)) {
            String missingName = "ORIGIN".equals(pending.missingRole())
                    ? extracted.originName() : extracted.destinationName();
            String missingRegion = "ORIGIN".equals(pending.missingRole())
                    ? extracted.originRegion() : extracted.destinationRegion();
            if (!hasText(missingName)) {
                completeText(sink, customerId, conversationNo, requestNo,
                        partialPromptText(pending.knownRole(), pending.knownPlaceName()),
                        partialPayloadJson(pending));
                return;
            }
            continuePartialEndpoint(sink, customerId, conversationNo, requestNo,
                    conditionVersion, missingName, missingRegion, pending);
            return;
        }
        if (hasOrigin != hasDestination) {
            if (VIA_ROUTE_PLAN.equals(extracted.intent()) && !hasText(extracted.viaPlaceName())) {
                completeText(sink, customerId, conversationNo, requestNo, ASK_FOR_VIA_PLACE, null);
                return;
            }
            preparePartialEndpoint(sink, customerId, conversationNo, requestNo, conditionVersion,
                    content, extracted, hasOrigin ? "ORIGIN" : "DESTINATION");
            return;
        }
        if (VIA_ROUTE_PLAN.equals(extracted.intent())) {
            handleViaRoutePlan(sink, customerId, conversationNo, requestNo,
                    conditionVersion, content, extracted);
            return;
        }
        if (!hasOrigin || !hasDestination) {
            completeText(sink, customerId, conversationNo, requestNo, ASK_FOR_ENDPOINTS, null);
            return;
        }
        RoutePreparationResponse prep = unwrap(mapRouteAi.prepare(new RoutePreparationRequest(
                customerId, conversationNo, requestNo, conditionVersion,
                extracted.originName(), extracted.originRegion(),
                extracted.destinationName(), extracted.destinationRegion(),
                extracted.intent(), content)), "prepare");
        if (READY_FOR_ROUTE.equals(prep.status())) {
            completeDefaultRoute(sink, customerId, conversationNo, requestNo, conditionVersion);
        } else if (NEEDS_PLACE_CHOICE.equals(prep.status())) {
            completePlaceChoices(sink, customerId, conversationNo, requestNo, conditionVersion,
                    conditionVersion, prep);
        } else {
            completeText(sink, customerId, conversationNo, requestNo,
                    buildClarification(extracted, prep), null);
        }
    }

    private void preparePartialEndpoint(AiTurnEventSink sink, long customerId,
                                        String conversationNo, String requestNo,
                                        long conditionVersion, String content,
                                        ExtractEndpointsResponse extracted, String endpointRole) {
        String placeName = "ORIGIN".equals(endpointRole)
                ? extracted.originName() : extracted.destinationName();
        String placeRegion = "ORIGIN".equals(endpointRole)
                ? extracted.originRegion() : extracted.destinationRegion();
        RoutePreparationResponse prep = unwrap(mapRouteAi.preparePartial(
                new PartialEndpointPrepareRequest(customerId, conversationNo, requestNo,
                        conditionVersion, endpointRole, placeName, placeRegion,
                        extracted.intent(), content)), "preparePartial");
        if (WAITING_FOR_OTHER_ENDPOINT.equals(prep.status())) {
            completePartialPrompt(sink, customerId, conversationNo, requestNo,
                    prep.partialEndpoint(), extracted.viaPlaceName(), extracted.viaPlaceRegion());
        } else if (NEEDS_PLACE_CHOICE.equals(prep.status())) {
            completePlaceChoices(sink, customerId, conversationNo, requestNo,
                    conditionVersion, conditionVersion, prep, PARTIAL_ENDPOINT_FLOW,
                    extracted.viaPlaceName(), extracted.viaPlaceRegion());
        } else {
            completeText(sink, customerId, conversationNo, requestNo,
                    singleEndpointClarification(endpointRole, placeName, prep), null);
        }
    }

    private void continuePartialEndpoint(AiTurnEventSink sink, long customerId,
                                         String conversationNo, String requestNo,
                                         long currentConditionVersion,
                                         String placeName, String placeRegion,
                                         PartialEndpointPayload pending) {
        RoutePreparationResponse prep = unwrap(mapRouteAi.completePartial(
                new PartialEndpointCompleteRequest(customerId, conversationNo,
                        pending.followUpRequestNo(), pending.conditionVersion(), requestNo,
                        currentConditionVersion, pending.missingRole(), placeName, placeRegion)),
                "completePartial");
        if (READY_FOR_ROUTE.equals(prep.status())) {
            if (VIA_ROUTE_PLAN.equals(pending.intent())) {
                requestViaRoute(sink, customerId, conversationNo, requestNo,
                        null, currentConditionVersion, currentConditionVersion,
                        pending.viaPlaceName(), pending.viaPlaceRegion(), placeName);
            } else {
                completeDefaultRoute(sink, customerId, conversationNo, requestNo,
                        currentConditionVersion);
            }
        } else if (NEEDS_PLACE_CHOICE.equals(prep.status())) {
            String flow = VIA_ROUTE_PLAN.equals(pending.intent())
                    ? VIA_ROUTE_PLAN : DEFAULT_PLACE_CHOICE_FLOW;
            completePlaceChoices(sink, customerId, conversationNo, requestNo,
                    currentConditionVersion, currentConditionVersion, prep, flow,
                    pending.viaPlaceName(), pending.viaPlaceRegion());
        } else if ("CONTEXT_UNAVAILABLE".equals(prep.status())) {
            completeText(sink, customerId, conversationNo, requestNo,
                    CONFIRMATION_EXPIRED, null);
        } else {
            completeText(sink, customerId, conversationNo, requestNo,
                    singleEndpointClarification(pending.missingRole(), placeName, prep),
                    partialPayloadJson(pending));
        }
    }

    private String singleEndpointClarification(String endpointRole, String placeName,
                                               RoutePreparationResponse prep) {
        EndpointFeedbackView feedback = "ORIGIN".equals(endpointRole)
                ? prep.originFeedback() : prep.destinationFeedback();
        String label = "ORIGIN".equals(endpointRole) ? "起点" : "终点";
        if (feedback == null) {
            return "没有识别到明确的" + label + "，请补充更具体的地点名称。";
        }
        return endpointClarification(label, placeName, feedback)
                + "请补充城市、区县或更具体的地址。";
    }

    private void completePartialPrompt(AiTurnEventSink sink, long customerId,
                                       String conversationNo, String requestNo,
                                       com.sx.passengerapi.client.dto.maproute.MapRouteDtos.PartialEndpointView partial,
                                       String viaPlaceName, String viaPlaceRegion) {
        if (partial == null || !requestNo.equals(partial.followUpRequestNo())) {
            throw new IllegalStateException("单端点续接响应不完整");
        }
        PartialEndpointPayload payload = new PartialEndpointPayload(
                partial.conditionVersion(), partial.followUpRequestNo(), partial.knownRole(),
                partial.missingRole(), partial.knownPlaceName(), partial.intent(),
                viaPlaceName, viaPlaceRegion);
        completeText(sink, customerId, conversationNo, requestNo,
                partialPromptText(partial.knownRole(), partial.knownPlaceName()),
                partialPayloadJson(payload));
    }

    private static String partialPromptText(String knownRole, String knownPlaceName) {
        return "ORIGIN".equals(knownRole)
                ? "已记住起点「" + knownPlaceName + "」，请告诉我终点。"
                : "已记住终点「" + knownPlaceName + "」，请告诉我起点。";
    }

    private String partialPayloadJson(PartialEndpointPayload payload) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("conditionVersion", payload.conditionVersion());
        view.put("followUpRequestNo", payload.followUpRequestNo());
        view.put("knownRole", payload.knownRole());
        view.put("missingRole", payload.missingRole());
        view.put("knownPlaceName", payload.knownPlaceName());
        view.put("intent", payload.intent());
        if (hasText(payload.viaPlaceName())) view.put("viaPlaceName", payload.viaPlaceName());
        if (hasText(payload.viaPlaceRegion())) view.put("viaPlaceRegion", payload.viaPlaceRegion());
        try {
            return objectMapper.writeValueAsString(Map.of("partialEndpoint", view));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("单端点续接摘要序列化失败", e);
        }
    }

    /** 支持沿用当前路线两端，也支持本轮完整提供起终点与指定途经点。 */
    private void handleViaRoutePlan(AiTurnEventSink sink, long customerId,
                                    String conversationNo, String requestNo,
                                    long requestVersion, String content,
                                    ExtractEndpointsResponse extracted) {
        if (!hasText(extracted.viaPlaceName())) {
            completeText(sink, customerId, conversationNo, requestNo, ASK_FOR_VIA_PLACE, null);
            return;
        }
        boolean hasOrigin = hasText(extracted.originName());
        boolean hasDestination = hasText(extracted.destinationName());
        if (hasOrigin != hasDestination) {
            completeText(sink, customerId, conversationNo, requestNo, ASK_FOR_ENDPOINTS, null);
            return;
        }
        if (hasOrigin) {
            RoutePreparationResponse prep = unwrap(mapRouteAi.prepare(new RoutePreparationRequest(
                    customerId, conversationNo, requestNo, requestVersion,
                    extracted.originName(), extracted.originRegion(),
                    extracted.destinationName(), extracted.destinationRegion(),
                    extracted.intent(), content)), "prepareViaEndpoints");
            if (READY_FOR_ROUTE.equals(prep.status())) {
                requestViaRoute(sink, customerId, conversationNo, requestNo,
                        null, requestVersion, requestVersion,
                        extracted.viaPlaceName(), extracted.viaPlaceRegion(), content);
            } else if (NEEDS_PLACE_CHOICE.equals(prep.status())) {
                completePlaceChoices(sink, customerId, conversationNo, requestNo,
                        requestVersion, requestVersion, prep, VIA_ROUTE_PLAN,
                        extracted.viaPlaceName(), extracted.viaPlaceRegion());
            } else {
                completeText(sink, customerId, conversationNo, requestNo,
                        buildClarification(extracted, prep), null);
            }
            return;
        }
        String routeRef = latestPersistedRouteRef(customerId, conversationNo);
        if (routeRef == null) {
            completeText(sink, customerId, conversationNo, requestNo,
                    CURRENT_ROUTE_UNAVAILABLE, null);
            return;
        }
        requestViaRoute(sink, customerId, conversationNo, requestNo,
                routeRef, 0, requestVersion,
                extracted.viaPlaceName(), extracted.viaPlaceRegion(), content);
    }

    private void requestViaRoute(AiTurnEventSink sink, long customerId,
                                 String conversationNo, String requestNo,
                                 String routeRef, long conditionVersion,
                                 long requestVersion, String placeName,
                                 String placeRegion, String originalUserText) {
        ViaRouteResponse result = unwrap(mapRouteAi.viaRoute(new ViaRouteRequest(
                customerId, conversationNo, routeRef, conditionVersion, requestVersion,
                placeName, placeRegion, originalUserText)), "viaRoute");
        completeViaRouteResult(sink, customerId, conversationNo, requestNo,
                requestVersion, placeName, result);
    }

    private void completeViaRouteResult(AiTurnEventSink sink, long customerId,
                                        String conversationNo, String requestNo,
                                        long requestVersion, String requestedPlaceName,
                                        ViaRouteResponse result) {
        switch (result.status()) {
            case "ROUTE_READY" -> completeRouteCard(sink, customerId, conversationNo,
                    requestNo, result.routeCard());
            case NEEDS_PLACE_CHOICE -> completeViaPlaceChoices(sink, customerId, conversationNo,
                    requestNo, requestVersion, requestedPlaceName, result, VIA_ROUTE_PLAN, null);
            case "PLACE_NOT_FOUND" -> completeText(sink, customerId, conversationNo, requestNo,
                    "没有找到「" + requestedPlaceName + "」，请补充城市或更具体的名称。", null);
            case "PLACE_UNVERIFIABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    "暂时无法使用「" + requestedPlaceName + "」规划路线："
                            + detailOrFallback(result.detail(), "地图地点信息不足") + "。", null);
            case "ROUTE_NOT_FOUND" -> completeText(sink, customerId, conversationNo, requestNo,
                    "没有找到已验证经过「" + requestedPlaceName + "」的可达路线。", null);
            case "CONTEXT_UNAVAILABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    "路线条件已失效，请重新告诉我完整的起点和终点。", null);
            default -> throw new IllegalStateException("未知的指定途经点规划状态: " + result.status());
        }
    }

    private void completeViaPlaceChoices(AiTurnEventSink sink, long customerId,
                                         String conversationNo, String requestNo,
                                         long requestVersion, String requestedPlaceName,
                                         ViaRouteResponse result, String flow,
                                         CheckContext context) {
        var choices = result.placeChoices();
        if (choices == null || choices.choices() == null || choices.choices().isEmpty()
                || result.conditionVersion() <= 0) {
            throw new IllegalStateException("指定途经点选项响应不完整");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskNo", choices.taskNo());
        view.put("role", choices.role());
        view.put("choices", choices.choices());
        view.put("expiresAt", choices.expiresAt());
        view.put("conditionVersion", result.conditionVersion());
        view.put("requestVersion", requestVersion);
        view.put("flow", flow);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of("placeChoices", view));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指定途经点选项摘要序列化失败", e);
        }
        String text = "地图找到以下可能对应「" + requestedPlaceName + "」的地点，请确认具体地点。";
        sendChoiceMessage(sink, customerId, conversationNo, requestNo, "PLACE_CHOICES",
                "place.choices", text, payloadJson, context);
    }

    /**
     * 天气 skill：单城市、单日、文本回答。缺城市或日期超出范围时追问/说明，
     * 不调用地图；只有城市明确时才请求 map-service，天气事实不从模型文本生成。
     */
    private void handleWeatherQuery(AiTurnEventSink sink, long customerId,
                                    String conversationNo, String requestNo,
                                    String content, ExtractEndpointsResponse extracted,
                                    WeatherContextResolver.Context weatherContext) {
        Integer requestedOffset = extracted.weatherDayOffset();
        if (requestedOffset == null) {
            requestedOffset = dayOffsetFromText(content);
        }
        if (requestedOffset == null) {
            requestedOffset = weatherContext.pendingDayOffset();
        }
        if (requestedOffset != null && (requestedOffset < 0 || requestedOffset > 3)) {
            completeText(sink, customerId, conversationNo, requestNo, WEATHER_DATE_RANGE, null);
            return;
        }
        String requestedPlace = extracted.weatherPlaceName();
        boolean inferred = false;
        if (!hasText(requestedPlace)) {
            requestedPlace = weatherContext.city();
            if (!hasText(requestedPlace)) {
                completeWeatherAsk(sink, customerId, conversationNo, requestNo, requestedOffset);
                return;
            }
            inferred = true;
        }
        String queryPlace = inferred
                ? WeatherContextResolver.displayCity(requestedPlace) : requestedPlace;
        if (!hasText(queryPlace)) {
            completeWeatherAsk(sink, customerId, conversationNo, requestNo, requestedOffset);
            return;
        }
        int dayOffset = requestedOffset == null ? 0 : requestedOffset;
        WeatherForecastResponse result = unwrap(mapWeatherAi.forecast(
                new WeatherForecastRequest(requestNo, queryPlace,
                        inferred ? null : extracted.weatherPlaceRegion(), dayOffset)),
                "weatherForecast");
        switch (result.status()) {
            case WeatherForecastResponse.STATUS_OK -> {
                String text = WeatherAnswerFormatter.text(result, dayOffset, queryPlace);
                if (inferred) {
                    text = "按你刚才说的" + queryPlace + "查：" + text;
                }
                completeText(sink, customerId, conversationNo, requestNo, text,
                        weatherPayloadJson(result));
            }
            case WeatherForecastResponse.STATUS_PLACE_REQUIRED ->
                    completeWeatherAsk(sink, customerId, conversationNo, requestNo,
                            requestedOffset);
            case WeatherForecastResponse.STATUS_PLACE_NOT_FOUND ->
                    completeText(sink, customerId, conversationNo, requestNo,
                            "没有找到「" + queryPlace
                                    + "」这个城市，请补充省份或换一个名称。", null);
            case WeatherForecastResponse.STATUS_DATE_OUT_OF_RANGE ->
                    completeText(sink, customerId, conversationNo, requestNo,
                            WEATHER_DATE_RANGE, null);
            default -> throw new IllegalStateException("未知的天气查询状态: " + result.status());
        }
    }

    /**
     * 上一轮是天气追问时，本轮只回一个城市应继续天气；若乘客明确给出路线动作或两端，
     * 返回 null，让路线分支接管。
     */
    private ExtractEndpointsResponse weatherReplyFromPending(
            ExtractEndpointsResponse extracted, String content,
            WeatherContextResolver.Context context) {
        if (hasText(extracted.destinationName())
                || hasText(extracted.viaPlaceName())
                || hasText(extracted.checkPlaceName())
                || containsRouteVerb(content)) {
            return null;
        }
        String city = hasText(extracted.weatherPlaceName())
                ? extracted.weatherPlaceName()
                : hasText(extracted.originName()) ? extracted.originName() : null;
        if (!hasText(city) && isBareCityText(content)) {
            city = content.strip();
        }
        if (!hasText(city)) {
            return null;
        }
        Integer offset = extracted.weatherDayOffset() != null
                ? extracted.weatherDayOffset() : context.pendingDayOffset();
        return new ExtractEndpointsResponse(null, null, null, null, WEATHER_QUERY,
                null, null, null, null, null, city, null, offset);
    }

    private static Integer dayOffsetFromText(String content) {
        if (content.contains("大后天")) return 3;
        if (content.contains("后天")) return 2;
        if (content.contains("明天")) return 1;
        if (content.contains("今天")) return 0;
        return null;
    }

    private static boolean containsRouteVerb(String content) {
        return content.contains("从") || content.contains("到")
                || content.contains("规划") || content.contains("路线")
                || content.contains("经过") || content.contains("途经")
                || content.contains("改走") || content.contains("导航")
                || content.contains("怎么走") || content.contains("去")
                || content.contains("回");
    }

    private static boolean isBareCityText(String content) {
        String text = content.strip();
        return text.length() >= 2 && text.length() <= 20
                && !containsRouteVerb(text)
                && !text.contains("今天") && !text.contains("明天")
                && !text.contains("后天") && !text.contains("天气")
                && !text.contains("下雨") && !text.contains("气温")
                && !text.contains("度") && !text.contains("呢");
    }

    /** 天气追问也要把原始日期偏移写进服务端上下文，下一轮乘客只回城市时才能续接。 */
    private void completeWeatherAsk(AiTurnEventSink sink, long customerId,
                                    String conversationNo, String requestNo,
                                    Integer dayOffset) {
        String text = weatherAskText(dayOffset);
        String payloadJson = weatherAskPayload(dayOffset);
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "TEXT", text, payloadJson)), "complete");
        sink.content("answer.completed", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "TEXT", text,
                clientPayload(payloadJson), done.createdAt())));
    }

    private static String weatherAskText(Integer dayOffset) {
        if (dayOffset != null && dayOffset >= 0 && dayOffset <= 3) {
            return "请告诉我城市，例如：杭州" + weatherDayLabel(dayOffset) + "天气。";
        }
        return ASK_FOR_WEATHER;
    }

    private String weatherAskPayload(Integer dayOffset) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (dayOffset != null) {
            context.put("dayOffset", dayOffset);
        }
        try {
            return objectMapper.writeValueAsString(Map.of(WEATHER_CONTEXT_FIELD, context));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("天气追问上下文序列化失败", e);
        }
    }

    private static String weatherDayLabel(int dayOffset) {
        return switch (dayOffset) {
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            case 3 -> "大后天";
            default -> "";
        };
    }

    private String weatherPayloadJson(WeatherForecastResponse result) {
        try {
            return objectMapper.writeValueAsString(WeatherAnswerFormatter.payload(result));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("天气结果序列化失败", e);
        }
    }

    /** 当前路线优先取自本会话已持久化的最新路线卡，模型不能提交 routeRef。 */
    private void handleCurrentRouteCheck(AiTurnEventSink sink, long customerId,
                                         String conversationNo, String requestNo,
                                         long requestVersion, String content,
                                         ExtractEndpointsResponse extracted) {
        if (!hasText(extracted.checkPlaceName())) {
            completeText(sink, customerId, conversationNo, requestNo, ASK_FOR_CHECK_PLACE, null);
            return;
        }
        String routeRef = latestPersistedRouteRef(customerId, conversationNo);
        if (routeRef == null) {
            CheckContext context = checkContext(CURRENT_ROUTE_CHECK, extracted, null, false, false);
            if (hasText(extracted.originName()) && hasText(extracted.destinationName())) {
                prepareCheckWithEndpoints(sink, customerId, conversationNo, requestNo,
                        requestVersion, content, extracted, context);
            } else {
                askForCheckEndpoints(sink, customerId, conversationNo, requestNo, context);
            }
            return;
        }
        CheckContext context = checkContext(CURRENT_ROUTE_CHECK, extracted, routeRef, true, false);
        CurrentRouteCheckResponse result = unwrap(mapRouteAi.checkCurrentRoute(
                new CurrentRouteCheckRequest(customerId, conversationNo, routeRef, requestVersion,
                        extracted.checkPlaceName(), extracted.checkPlaceRegion(),
                        extracted.checkMode(), content)),
                "checkCurrentRoute");
        if ("CONTEXT_UNAVAILABLE".equals(result.status())) {
            // 旧路线快照已失效：按“没有可核查路线”重新生成一条，再核查这条新路线。
            CheckContext renewed = context.withRouteRef(null, false).asPreviousRouteExpired();
            if (hasText(extracted.originName()) && hasText(extracted.destinationName())) {
                prepareCheckWithEndpoints(sink, customerId, conversationNo, requestNo,
                        requestVersion, content, extracted, renewed);
            } else {
                askForCheckEndpoints(sink, customerId, conversationNo, requestNo, renewed);
            }
            return;
        }
        completeCurrentRouteCheckResult(sink, customerId, conversationNo, requestNo,
                requestVersion, extracted.checkPlaceName(), result, context);
    }

    /** 核查类意图的上下文：routeShown 表示被核查的路线是否已经展示给乘客。 */
    private static CheckContext checkContext(String intent, ExtractEndpointsResponse extracted,
                                             String routeRef, boolean routeShown,
                                             boolean previousRouteExpired) {
        return new CheckContext(intent, extracted.checkPlaceName(), extracted.checkPlaceRegion(),
                normalizeCheckMode(extracted.checkMode()), routeRef, routeShown, previousRouteExpired);
    }

    /**
     * 核查类意图的起终点准备：地图定位两端后按原意图继续。
     * 端点仍有歧义时先返回地点选项，选项载荷携带同一份核查上下文。
     */
    private void prepareCheckWithEndpoints(AiTurnEventSink sink, long customerId,
                                           String conversationNo, String requestNo,
                                           long conditionVersion, String content,
                                           ExtractEndpointsResponse extracted,
                                           CheckContext context) {
        RoutePreparationResponse prep = unwrap(mapRouteAi.prepare(new RoutePreparationRequest(
                customerId, conversationNo, requestNo, conditionVersion,
                extracted.originName(), extracted.originRegion(),
                extracted.destinationName(), extracted.destinationRegion(),
                context.intent(), content)), "prepareCheck");
        if (READY_FOR_ROUTE.equals(prep.status())) {
            if (ROUTE_EXISTS.equals(context.intent())) {
                requestRouteExists(sink, customerId, conversationNo, requestNo, conditionVersion,
                        content, context.placeName(), context.placeRegion(), null, conditionVersion);
            } else {
                createDefaultRouteThenCheck(sink, customerId, conversationNo, requestNo,
                        conditionVersion, content, context);
            }
        } else if (NEEDS_PLACE_CHOICE.equals(prep.status())) {
            completePlaceChoices(sink, customerId, conversationNo, requestNo,
                    conditionVersion, conditionVersion, prep, DEFAULT_PLACE_CHOICE_FLOW,
                    null, null, context);
        } else {
            completeText(sink, customerId, conversationNo, requestNo,
                    buildCheckClarification(extracted, prep, context), null, context);
        }
    }

    /** 端点定位失败的追问仍保留核查上下文，乘客补齐端点后继续原核查。 */
    private String buildCheckClarification(ExtractEndpointsResponse extracted,
                                           RoutePreparationResponse prep, CheckContext context) {
        String nextStep = ROUTE_EXISTS.equals(context.intent())
                ? "查询有没有经过「" + context.placeName() + "」的路线"
                : "生成路线并核查是否经过「" + context.placeName() + "」";
        return endpointClarification("起点", extracted.originName(), prep.originFeedback())
                + endpointClarification("终点", extracted.destinationName(), prep.destinationFeedback())
                + "请补充后我继续" + nextStep + "。";
    }

    /** 缺少两端时的核查追问：上下文随追问消息落库，下一轮直接续接。 */
    private void askForCheckEndpoints(AiTurnEventSink sink, long customerId,
                                      String conversationNo, String requestNo,
                                      CheckContext context) {
        completeText(sink, customerId, conversationNo, requestNo,
                askForCheckEndpointsText(context), null, context);
    }

    private static String askForCheckEndpointsText(CheckContext context) {
        String prefix = context.previousRouteExpired() ? "之前的路线已失效，" : "";
        return ROUTE_EXISTS.equals(context.intent())
                ? prefix + "请告诉我完整的起点和终点，我来查询有没有经过「"
                + context.placeName() + "」的路线。"
                : prefix + "当前会话还没有可核查的路线。请告诉我完整的起点和终点，"
                + "我将先生成路线，再核查是否经过「" + context.placeName() + "」。";
    }

    /**
     * 没有可核查路线时先按本轮两端生成默认路线，再核查这条新路线；
     * 路线卡与核查结论同轮下发，避免把结论挂到乘客还没看到的路线。
     */
    private void createDefaultRouteThenCheck(AiTurnEventSink sink, long customerId,
                                             String conversationNo, String requestNo,
                                             long conditionVersion, String content,
                                             CheckContext context) {
        RouteCardDto card = createDefaultRoute(customerId, conversationNo, conditionVersion);
        if (card == null) {
            completeText(sink, customerId, conversationNo, requestNo,
                    "路线条件已过期，请重新告诉我完整的起点和终点。", null);
            return;
        }
        CheckContext stored = context.withRouteRef(card.routeRef(), false);
        CurrentRouteCheckResponse result = unwrap(mapRouteAi.checkCurrentRoute(
                new CurrentRouteCheckRequest(customerId, conversationNo, card.routeRef(),
                        conditionVersion, context.placeName(), context.placeRegion(),
                        context.checkMode(), content)),
                "checkNewRoute");
        completeNewRouteCheckResult(sink, customerId, conversationNo, requestNo,
                conditionVersion, context.placeName(), result, card, stored);
    }

    /** 新生成路线的核查结论只能随 route.card 下发，客户端据此知道核查的是哪条路线。 */
    private void completeNewRouteCheckResult(AiTurnEventSink sink, long customerId,
                                             String conversationNo, String requestNo,
                                             long requestVersion, String requestedPlaceName,
                                             CurrentRouteCheckResponse result, RouteCardDto card,
                                             CheckContext context) {
        String routeRef = card.routeRef();
        switch (result.status()) {
            case "CHECKED" -> completeRouteCardWithCheck(sink, customerId, conversationNo,
                    requestNo, card, routeCheckView(routeRef, result, requestedPlaceName),
                    NEW_DEFAULT_ROUTE_PREFIX, currentRouteVerdictText(result, requestedPlaceName));
            case NEEDS_PLACE_CHOICE -> completeCurrentRoutePlaceChoices(sink, customerId,
                    conversationNo, requestNo, requestVersion, requestedPlaceName, result, context);
            case NEEDS_CHECK_MODE_CHOICE -> completeCurrentRouteModeChoices(sink, customerId,
                    conversationNo, requestNo, requestVersion, requestedPlaceName, result, context);
            case "PLACE_NOT_FOUND" -> completeRouteCardWithCheck(sink, customerId, conversationNo,
                    requestNo, card,
                    unverifiableCheckView(routeRef, requestedPlaceName, context.placeRegion(),
                            context.checkMode(),
                            "没有找到「" + requestedPlaceName + "」，请补充城市或更具体的名称。"),
                    NEW_DEFAULT_ROUTE_PREFIX, "暂时无法核查「" + requestedPlaceName
                            + "」：没有找到该地点，请补充城市或更具体的名称。");
            case "PLACE_UNVERIFIABLE" -> completeRouteCardWithCheck(sink, customerId, conversationNo,
                    requestNo, card,
                    unverifiableCheckView(routeRef, requestedPlaceName, context.placeRegion(),
                            context.checkMode(),
                            detailOrFallback(result.detail(), "地图地点信息不足")),
                    NEW_DEFAULT_ROUTE_PREFIX, "暂时无法核查「" + requestedPlaceName + "」："
                            + detailOrFallback(result.detail(), "地图地点信息不足") + "。");
            case "CONTEXT_UNAVAILABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    "路线条件已过期，请重新告诉我完整的起点和终点。", null);
            default -> throw new IllegalStateException("未知的当前路线核查状态: " + result.status());
        }
    }

    private void completeCurrentRouteCheckResult(AiTurnEventSink sink, long customerId,
                                                 String conversationNo, String requestNo,
                                                 long requestVersion, String requestedPlaceName,
                                                 CurrentRouteCheckResponse result,
                                                 CheckContext context) {
        switch (result.status()) {
            case "CHECKED" -> emitCompletedCheck(sink, customerId, conversationNo, requestNo,
                    requestedPlaceName, result, context);
            case NEEDS_PLACE_CHOICE -> completeCurrentRoutePlaceChoices(sink, customerId,
                    conversationNo, requestNo, requestVersion, requestedPlaceName, result, context);
            case NEEDS_CHECK_MODE_CHOICE -> completeCurrentRouteModeChoices(sink, customerId,
                    conversationNo, requestNo, requestVersion, requestedPlaceName, result, context);
            case "PLACE_NOT_FOUND" -> completeText(sink, customerId, conversationNo, requestNo,
                    "没有找到「" + requestedPlaceName + "」，请补充城市或更具体的名称。", null);
            case "PLACE_UNVERIFIABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    "暂时无法核查「" + requestedPlaceName + "」：" + detailOrFallback(
                            result.detail(), "地图地点信息不足") + "。", null);
            case "CONTEXT_UNAVAILABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    context != null && !context.routeShown()
                            ? "刚生成的路线已失效，请重新告诉我完整的起点和终点。"
                            : CURRENT_ROUTE_UNAVAILABLE, null);
            default -> throw new IllegalStateException("未知的当前路线核查状态: " + result.status());
        }
    }

    /** 已展示的路线直接回答结论；本轮新建但尚未展示的路线先补发 route.card。 */
    private void emitCompletedCheck(AiTurnEventSink sink, long customerId, String conversationNo,
                                    String requestNo, String requestedPlaceName,
                                    CurrentRouteCheckResponse result, CheckContext context) {
        String verdictText = currentRouteVerdictText(result, requestedPlaceName);
        String routeRef = context == null ? null : context.routeRef();
        if (context != null && !context.routeShown() && hasText(routeRef)) {
            resumeRouteCardWithCheck(sink, customerId, conversationNo, requestNo, routeRef,
                    routeCheckView(routeRef, result, requestedPlaceName),
                    NEW_DEFAULT_ROUTE_PREFIX, verdictText);
            return;
        }
        completeText(sink, customerId, conversationNo, requestNo, verdictText,
                routeCheckPayloadJson(routeCheckView(routeRef, result, requestedPlaceName)));
    }

    /** 查询有无经过指定地点的路线：只给存在性结论，不展示路线卡。 */
    private void handleRouteExists(AiTurnEventSink sink, long customerId, String conversationNo,
                                   String requestNo, long requestVersion, String content,
                                   ExtractEndpointsResponse extracted) {
        if (!hasText(extracted.checkPlaceName())) {
            completeText(sink, customerId, conversationNo, requestNo,
                    ASK_FOR_ROUTE_EXISTS_PLACE, null);
            return;
        }
        CheckContext context = checkContext(ROUTE_EXISTS, extracted, null, false, false);
        // 本轮未重述两端时，可沿用本会话最新路线；地图服务仍会校验快照归属、条件和有效期。
        if (!hasText(extracted.originName()) && !hasText(extracted.destinationName())) {
            String routeRef = latestPersistedRouteRef(customerId, conversationNo);
            if (routeRef != null) {
                requestRouteExists(sink, customerId, conversationNo, requestNo,
                        requestVersion, content, extracted.checkPlaceName(),
                        extracted.checkPlaceRegion(), routeRef, 0);
                return;
            }
        }
        if (!hasText(extracted.originName()) || !hasText(extracted.destinationName())) {
            askForCheckEndpoints(sink, customerId, conversationNo, requestNo, context);
            return;
        }
        prepareCheckWithEndpoints(sink, customerId, conversationNo, requestNo,
                requestVersion, content, extracted, context);
    }

    /** 复用途经点算路能力：验证能否给出经过该地点的路线，但不展示路线卡。 */
    private void requestRouteExists(AiTurnEventSink sink, long customerId, String conversationNo,
                                    String requestNo, long requestVersion, String content,
                                    String placeName, String placeRegion, String routeRef,
                                    long conditionVersion) {
        CheckContext context = new CheckContext(ROUTE_EXISTS, placeName, placeRegion, null,
                routeRef, false, false);
        ViaRouteResponse result = unwrap(mapRouteAi.viaRoute(new ViaRouteRequest(
                customerId, conversationNo, routeRef, conditionVersion, requestVersion,
                placeName, placeRegion, content)), "routeExists");
        completeRouteExistsResult(sink, customerId, conversationNo, requestNo,
                requestVersion, placeName, result, context);
    }

    private void completeRouteExistsResult(AiTurnEventSink sink, long customerId,
                                           String conversationNo, String requestNo,
                                           long requestVersion, String requestedPlaceName,
                                           ViaRouteResponse result, CheckContext context) {
        switch (result.status()) {
            case "ROUTE_READY" -> completeText(sink, customerId, conversationNo, requestNo,
                    routeExistsText(context, "FOUND", requestedPlaceName, result.detail()),
                    routeExistsPayloadJson(context, "FOUND", requestedPlaceName, result.detail()));
            case "ROUTE_NOT_FOUND" -> completeText(sink, customerId, conversationNo, requestNo,
                    routeExistsText(context, "NOT_FOUND_IN_QUERY", requestedPlaceName,
                            result.detail()),
                    routeExistsPayloadJson(context, "NOT_FOUND_IN_QUERY", requestedPlaceName,
                            result.detail()));
            case "PLACE_NOT_FOUND" -> completeText(sink, customerId, conversationNo, requestNo,
                    "没有找到「" + requestedPlaceName + "」，请补充城市或更具体的名称。",
                    routeExistsPayloadJson(context, "UNVERIFIABLE", requestedPlaceName,
                            "PLACE_NOT_FOUND"));
            case "PLACE_UNVERIFIABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    "暂时无法查询经过「" + requestedPlaceName + "」的路线："
                            + detailOrFallback(result.detail(), "地图地点信息不足") + "。",
                    routeExistsPayloadJson(context, "UNVERIFIABLE", requestedPlaceName,
                            detailOrFallback(result.detail(), "地图地点信息不足")));
            case "CONTEXT_UNAVAILABLE" -> askForCheckEndpoints(sink, customerId, conversationNo,
                    requestNo, context.withRouteRef(null, false).asPreviousRouteExpired());
            case NEEDS_PLACE_CHOICE -> completeViaPlaceChoices(sink, customerId, conversationNo,
                    requestNo, requestVersion, requestedPlaceName, result, ROUTE_EXISTS, context);
            default -> throw new IllegalStateException("未知的路线存在性查询状态: " + result.status());
        }
    }

    /** 只陈述本次查询范围内的结论，不推断不存在经过该地点的其他路线。 */
    private static String routeExistsText(CheckContext context, String conclusion,
                                          String requestedPlaceName, String detail) {
        String placeName = hasText(requestedPlaceName) ? requestedPlaceName : context.placeName();
        return switch (conclusion) {
            case "FOUND" -> "找到一条从当前起点到终点、经过「" + placeName + "」的路线"
                    + (hasText(detail) ? "（" + detail.strip() + "）" : "") + "。";
            case "NOT_FOUND_IN_QUERY" -> "本次查询没有找到经过「" + placeName
                    + "」的已验证路线" + (hasText(detail) ? "（" + detail.strip() + "）" : "")
                    + "，可以补充城市或换一个地点再试。";
            default -> "暂时无法确认有没有经过「" + placeName + "」的路线："
                    + (hasText(detail) ? detail.strip() : "地图地点信息不足") + "。";
        };
    }

    private String routeExistsPayloadJson(CheckContext context, String conclusion,
                                          String requestedPlaceName, String detail) {
        Map<String, Object> view = new LinkedHashMap<>();
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("name", hasText(requestedPlaceName) ? requestedPlaceName : context.placeName());
        if (hasText(context.placeRegion())) {
            place.put("city", context.placeRegion());
        }
        view.put("place", place);
        view.put("conclusion", conclusion);
        if (hasText(detail)) {
            view.put("detail", detail.strip());
        }
        try {
            return objectMapper.writeValueAsString(Map.of("routeExists", view));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("路线存在性结论序列化失败", e);
        }
    }

    /**
     * 路线核查结论视图：只描述被核查的路线、目标地点与结论，
     * 不包含坐标、折线或导航步骤。
     */
    private Map<String, Object> routeCheckView(String routeRef, CurrentRouteCheckResponse result,
                                               String requestedPlaceName) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("routeRef", routeRef);
        view.put("status", result.status());
        view.put("verdict", result.verdict());
        if (hasText(result.checkMode())) {
            view.put("checkMode", result.checkMode());
        }
        if (hasText(result.reason())) {
            view.put("reason", result.reason());
        }
        if (hasText(result.detail())) {
            view.put("detail", result.detail());
        }
        view.put("target", result.target() == null
                ? targetFromRequestedName(requestedPlaceName, null)
                : objectMapper.convertValue(result.target(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        }));
        return view;
    }

    /** 地点未找到或不可核查时仍保留 routeCheck，客户端据此知道核查挂在哪条路线上。 */
    private static Map<String, Object> unverifiableCheckView(String routeRef, String requestedPlaceName,
                                                             String placeRegion, String checkMode,
                                                             String detail) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("routeRef", routeRef);
        view.put("status", "PLACE_UNVERIFIABLE");
        view.put("verdict", "UNVERIFIABLE");
        if (hasText(checkMode)) {
            view.put("checkMode", checkMode);
        }
        view.put("detail", detail);
        view.put("target", targetFromRequestedName(requestedPlaceName, placeRegion));
        return view;
    }

    private static Map<String, Object> targetFromRequestedName(String requestedPlaceName,
                                                               String placeRegion) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("name", hasText(requestedPlaceName) ? requestedPlaceName : "所选地点");
        if (hasText(placeRegion)) {
            target.put("city", placeRegion.strip());
        }
        return target;
    }

    private String routeCheckPayloadJson(Map<String, Object> routeCheck) {
        try {
            return objectMapper.writeValueAsString(Map.of("routeCheck", routeCheck));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("路线核查结论序列化失败", e);
        }
    }

    private String currentRouteVerdictText(CurrentRouteCheckResponse result,
                                           String requestedPlaceName) {
        String placeName = result.target() != null && hasText(result.target().name())
                ? result.target().name() : requestedPlaceName;
        String checkMode = hasText(result.checkMode()) ? result.checkMode() : "VISIT_PLACE";
        String relation = switch (checkMode) {
            case "PASS_NEARBY" -> "经过「" + placeName + "」附近";
            case "USE_EXIT" -> "从「" + placeName + "」驶出高速";
            case "ENTER_HIGHWAY" -> "从「" + placeName + "」驶入高速";
            case "VISIT_PLACE" -> "实际进入或通过「" + placeName + "」";
            default -> "经过「" + placeName + "」";
        };
        String detail = stripSentenceEnd(detailOrFallback(
                result.detail(), "当前路线缺少足够的地图证据"));
        if ("PASS_NEARBY".equals(checkMode)) {
            return nearbyRelationText(placeName, result, detail);
        }
        return switch (result.verdict()) {
            case "PASSED" -> "根据当前路线的地图信息，这条路线会" + relation + "（" + detail + "）。";
            case "NOT_PASSED" -> "根据当前路线的地图信息，这条路线不会" + relation + "（" + detail + "）。";
            case "UNVERIFIABLE" -> "暂时无法确认当前路线是否会" + relation + "：" + detail + "。";
            case "CONFLICT" -> semanticConflictText(result.reason(), detail, placeName);
            default -> throw new IllegalStateException("未知的当前路线核查结论: " + result.verdict());
        };
    }

    /**
     * “附近经过”是最容易被读成“实际通过”的结论。正文先明确没有确认实际通过，
     * 再给距离/证据，最后提示需要核查具体入口/出口时应如何补充，避免用户看走眼。
     */
    private static String nearbyRelationText(String placeName, CurrentRouteCheckResponse result,
                                             String detail) {
        String kind = placeKind(result);
        return switch (result.verdict()) {
            case "PASSED" -> "根据当前路线的地图信息，这条路线只是从「" + placeName
                    + "」附近经过，不能确认实际通过" + kind + "。具体来说，" + detail + "。"
                    + nearbyCheckHint(result);
            case "NOT_PASSED" -> "根据当前路线的地图信息，这条路线没有经过「" + placeName
                    + "」附近。具体来说，" + detail + "。";
            case "UNVERIFIABLE" -> "暂时无法确认当前路线是否从「" + placeName
                    + "」附近经过：" + detail + "。地图证据不足，不能当作已经经过或通过该地点。";
            case "CONFLICT" -> semanticConflictText(result.reason(), detail, placeName);
            default -> throw new IllegalStateException("未知的当前路线核查结论: " + result.verdict());
        };
    }

    private static String placeKind(CurrentRouteCheckResponse result) {
        String type = result.target() == null ? null : result.target().businessType();
        if ("TOLL_STATION".equals(type)) return "该收费站";
        if ("SERVICE_AREA".equals(type)) return "该服务区";
        if ("GAS_STATION".equals(type)) return "该加油站";
        return "该地点";
    }

    private static String nearbyCheckHint(CurrentRouteCheckResponse result) {
        String type = result.target() == null ? null : result.target().businessType();
        if ("TOLL_STATION".equals(type)) {
            return "如果你要核查的是某个具体入口/出口，请补充入口/出口名称。";
        }
        if ("SERVICE_AREA".equals(type) || "GAS_STATION".equals(type)) {
            return "如果你要核查的是某个具体入口，请补充入口名称。";
        }
        return "如果你要核查的是更具体的地点，请补充地点名称。";
    }

    private static String stripSentenceEnd(String value) {
        String text = value == null ? "" : value.strip();
        while (text.endsWith("。") || text.endsWith(".")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }

    private static String semanticConflictText(String reason, String detail, String placeName) {
        if ("ALREADY_ON_HIGHWAY_BEFORE_TARGET".equals(reason)) {
            return detail + "。如果你想继续核查，可以确认路线是否经过“" + placeName
                    + "”附近；如果想查询下高速，请选择对应的出口地点。";
        }
        return "当前提问与所选地点或路线状态不一致：" + detail + "。请重新确认要核查的地点或动作。";
    }

    private void completeCurrentRouteModeChoices(AiTurnEventSink sink, long customerId,
                                                  String conversationNo, String requestNo,
                                                  long requestVersion, String requestedPlaceName,
                                                  CurrentRouteCheckResponse result,
                                                  CheckContext context) {
        var options = result.modeChoices();
        if (options == null || options.choices() == null || options.choices().size() < 2) {
            throw new IllegalStateException("当前路线核查含义选项响应不完整");
        }
        String targetName = options.target() != null && hasText(options.target().name())
                ? options.target().name() : requestedPlaceName;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskNo", options.taskNo());
        view.put("target", options.target());
        view.put("choices", options.choices());
        view.put("expiresAt", options.expiresAt());
        view.put("requestVersion", requestVersion);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of("routeCheckChoices", view));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("当前路线核查含义选项序列化失败", e);
        }
        String text = "你说的“经过「" + targetName + "」”，是想确认以下哪一种情况？";
        sendChoiceMessage(sink, customerId, conversationNo, requestNo, "ROUTE_CHECK_CHOICES",
                "route-check.choices", text, payloadJson, context);
    }

    /** 当前路线地点选项沿用既有 PLACE_CHOICES 事件，并写入 flow 供选择轮次安全分流。 */
    private void completeCurrentRoutePlaceChoices(AiTurnEventSink sink, long customerId,
                                                  String conversationNo, String requestNo,
                                                  long requestVersion, String requestedPlaceName,
                                                  CurrentRouteCheckResponse result,
                                                  CheckContext context) {
        var choices = result.placeChoices();
        if (choices == null || choices.choices() == null || choices.choices().isEmpty()) {
            throw new IllegalStateException("当前路线核查地点选项响应缺少候选");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskNo", choices.taskNo());
        view.put("role", choices.role());
        view.put("choices", choices.choices());
        view.put("expiresAt", choices.expiresAt());
        view.put("requestVersion", requestVersion);
        view.put("flow", CURRENT_ROUTE_CHECK);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of("placeChoices", view));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("当前路线核查地点选项摘要序列化失败", e);
        }
        String text = "地图找到以下可能对应「" + requestedPlaceName
                + "」的可核查地点，请确认具体地点。";
        sendChoiceMessage(sink, customerId, conversationNo, requestNo, "PLACE_CHOICES",
                "place.choices", text, payloadJson, context);
    }

    /** 选项点击独立成一轮；旧任务、旧版本或越权选项均不能恢复地点身份。 */
    public void selectPlaceChoice(AiTurnEventSink sink, long customerId, String conversationNo,
                                  String taskNo, String placeChoiceId, long expectedRequestVersion,
                                  String choiceName, String choiceCity, String idempotencyKey) {
        String requestNo = null;
        String content;
        try {
            // 客户端名称只是兼容旧请求的展示提示，落库正文必须来自本会话已保存的选项摘要。
            content = persistedPlaceChoiceContent(customerId, conversationNo, taskNo,
                    placeChoiceId, expectedRequestVersion);
            if (content == null) {
                sink.turnFailed(new TurnFailedEvent(null, "AI_PLACE_CHOICE_INVALID",
                        "这组地点选项已失效，请重新选择或描述地点。"));
                return;
            }
            AiTurnBeginResponse begin = unwrap(passengerAi.begin(conversationNo,
                    new AiTurnBeginRequest(customerId, idempotencyKey, content)), "begin");
            requestNo = begin.requestNo();
            sink.turnStarted(new TurnStartedEvent(requestNo, begin.requestVersion(), content));
            if (begin.assistantReply() != null) {
                AssistantMessageView reply = begin.assistantReply();
                if ("FAILED".equals(reply.status())) {
                    sink.turnFailed(replayFailure(requestNo, reply));
                    return;
                }
                if (!"COMPLETED".equals(reply.status())) {
                    sink.turnFailed(new TurnFailedEvent(requestNo, "AI_INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
                    return;
                }
                emitCompletedReply(sink, customerId, conversationNo, reply);
                return;
            }
            PlaceChoicePayload previous = parsePlaceChoicePayload(begin.latestAssistantMessage());
            if (previous == null || !taskNo.equals(previous.taskNo())
                    || expectedRequestVersion != previous.requestVersion()
                    || !previous.choiceIds().contains(placeChoiceId)) {
                String expiredText = previous != null && CURRENT_ROUTE_CHECK.equals(previous.flow())
                        ? "这组核查地点选项已失效，请重新发起当前路线核查。"
                        : previous != null && ROUTE_EXISTS.equals(previous.flow())
                        ? "这组查询地点选项已失效，请重新说明要查询的地点。"
                        : previous != null && VIA_ROUTE_PLAN.equals(previous.flow())
                        ? "这组指定途经地点选项已失效，请重新说明途经要求。"
                        : previous != null && PARTIAL_ENDPOINT_FLOW.equals(previous.flow())
                        ? "这组地点选项已失效，请重新告诉我刚才提供的起点或终点。"
                        : "这组选项已失效，请重新告诉我起点和终点。";
                completeText(sink, customerId, conversationNo, requestNo,
                        expiredText, null);
            } else if (CURRENT_ROUTE_CHECK.equals(previous.flow())) {
                continueCurrentRouteCheckPlaceChoice(sink, customerId, conversationNo, requestNo,
                        begin.requestVersion(), taskNo, placeChoiceId, previous);
            } else if (ROUTE_EXISTS.equals(previous.flow())) {
                continueRouteExistsPlaceChoice(sink, customerId, conversationNo, requestNo,
                        begin.requestVersion(), taskNo, placeChoiceId, previous);
            } else if (VIA_ROUTE_PLAN.equals(previous.flow()) && "WAYPOINT".equals(previous.role())) {
                continueViaRoutePlaceChoice(sink, customerId, conversationNo, requestNo,
                        begin.requestVersion(), taskNo, placeChoiceId, previous);
            } else {
                RoutePreparationResponse prep = unwrap(mapRouteAi.selectPlace(new RoutePlaceChoiceRequest(
                        customerId, conversationNo, taskNo, placeChoiceId,
                        previous.conditionVersion(), previous.requestVersion(), begin.requestVersion(),
                        requestNo)), "selectPlace");
                if (WAITING_FOR_OTHER_ENDPOINT.equals(prep.status())) {
                    completePartialPrompt(sink, customerId, conversationNo, requestNo,
                            prep.partialEndpoint(), previous.viaPlaceName(), previous.viaPlaceRegion());
                } else if (READY_FOR_ROUTE.equals(prep.status())) {
                    CheckContext check = previous.checkContext();
                    if (check != null && ROUTE_EXISTS.equals(check.intent())) {
                        requestRouteExists(sink, customerId, conversationNo, requestNo,
                                begin.requestVersion(), content, check.placeName(),
                                check.placeRegion(), null, previous.conditionVersion());
                    } else if (check != null && CURRENT_ROUTE_CHECK.equals(check.intent())) {
                        createDefaultRouteThenCheck(sink, customerId, conversationNo, requestNo,
                                previous.conditionVersion(), content, check);
                    } else if (VIA_ROUTE_PLAN.equals(previous.flow())) {
                        requestViaRoute(sink, customerId, conversationNo, requestNo,
                                null, previous.conditionVersion(), begin.requestVersion(),
                                previous.viaPlaceName(), previous.viaPlaceRegion(), content);
                    } else {
                        completeDefaultRoute(sink, customerId, conversationNo, requestNo,
                                previous.conditionVersion());
                    }
                } else if (NEEDS_PLACE_CHOICE.equals(prep.status())) {
                    completePlaceChoices(sink, customerId, conversationNo, requestNo,
                            previous.conditionVersion(), begin.requestVersion(), prep,
                            previous.flow(), previous.viaPlaceName(), previous.viaPlaceRegion(),
                            previous.checkContext());
                } else if ("CONTEXT_UNAVAILABLE".equals(prep.status())) {
                    completeText(sink, customerId, conversationNo, requestNo,
                            "这组选项已过期，请重新告诉我起点和终点。", null);
                } else {
                    completeText(sink, customerId, conversationNo, requestNo,
                            "终点仍未定位清楚，请重新告诉我完整的起点和终点。", null);
                }
            }
            sink.turnCompleted(new TurnCompletedEvent(requestNo));
        } catch (Exception e) {
            TurnFailedEvent failed = classify(requestNo, e);
            log.warn("AI 客服地点选择失败 requestNo={} code={} type={}",
                    requestNo, failed.code(), e.getClass().getSimpleName(), e);
            finishFailedTurn(sink, customerId, conversationNo, requestNo, failed);
        }
    }

    private void continueViaRoutePlaceChoice(AiTurnEventSink sink, long customerId,
                                             String conversationNo, String requestNo,
                                             long currentRequestVersion, String taskNo,
                                             String placeChoiceId,
                                             PlaceChoicePayload previous) {
        ViaRouteResponse result = unwrap(mapRouteAi.selectViaRoutePlace(
                new ViaRoutePlaceChoiceRequest(customerId, conversationNo,
                        previous.conditionVersion(), previous.requestVersion(),
                        currentRequestVersion, taskNo, placeChoiceId)),
                "selectViaRoutePlace");
        completeViaRouteResult(sink, customerId, conversationNo, requestNo,
                currentRequestVersion, "所选地点", result);
    }

    private void continueCurrentRouteCheckPlaceChoice(AiTurnEventSink sink, long customerId,
                                                      String conversationNo, String requestNo,
                                                      long currentRequestVersion, String taskNo,
                                                      String placeChoiceId,
                                                      PlaceChoicePayload previous) {
        CheckContext previousContext = previous.checkContext();
        // 本轮新生成但尚未展示的路线不在历史消息里，routeRef 只能来自上一轮的核查上下文。
        String routeRef = previousContext != null && hasText(previousContext.routeRef())
                ? previousContext.routeRef() : latestPersistedRouteRef(customerId, conversationNo);
        if (routeRef == null) {
            completeText(sink, customerId, conversationNo, requestNo, CURRENT_ROUTE_UNAVAILABLE, null);
            return;
        }
        CurrentRouteCheckResponse result = unwrap(mapRouteAi.selectCurrentRouteCheckPlace(
                new CurrentRouteCheckPlaceChoiceRequest(customerId, conversationNo, routeRef,
                        previous.requestVersion(), currentRequestVersion, taskNo, placeChoiceId)),
                "selectCurrentRouteCheckPlace");
        completeCurrentRouteCheckResult(sink, customerId, conversationNo, requestNo,
                currentRequestVersion, "所选地点", result,
                previousContext == null ? null
                        : previousContext.withRouteRef(routeRef, previousContext.routeShown()));
    }

    /** 存在性查询的地点选项：复用去程算路复验地点身份，但只回答有无，不展示路线卡。 */
    private void continueRouteExistsPlaceChoice(AiTurnEventSink sink, long customerId,
                                                String conversationNo, String requestNo,
                                                long currentRequestVersion, String taskNo,
                                                String placeChoiceId,
                                                PlaceChoicePayload previous) {
        CheckContext context = previous.checkContext();
        if (context == null) {
            completeText(sink, customerId, conversationNo, requestNo,
                    "这组查询地点选项已失效，请重新说明要查询的地点。", null);
            return;
        }
        ViaRouteResponse result = unwrap(mapRouteAi.selectViaRoutePlace(
                new ViaRoutePlaceChoiceRequest(customerId, conversationNo,
                        previous.conditionVersion(), previous.requestVersion(),
                        currentRequestVersion, taskNo, placeChoiceId)),
                "selectRouteExistsPlace");
        completeRouteExistsResult(sink, customerId, conversationNo, requestNo,
                currentRequestVersion, context.placeName(), result, context);
    }

    /**
     * “经过”的含义选择独立成一轮。地图地点与路线都从服务端短时任务恢复，客户端提供的
     * checkMode 只有在上一条已持久化选项中存在时才会继续核查。
     */
    public void selectCurrentRouteCheckMode(AiTurnEventSink sink,
                                            long customerId,
                                            String conversationNo,
                                            String taskNo,
                                            String checkMode,
                                            long expectedRequestVersion,
                                            String choiceLabel,
                                            String idempotencyKey) {
        String requestNo = null;
        String content = "我想确认：" + (hasText(choiceLabel) ? choiceLabel.strip() : "所选情况");
        try {
            AiTurnBeginResponse begin = unwrap(passengerAi.begin(conversationNo,
                    new AiTurnBeginRequest(customerId, idempotencyKey, content)), "begin");
            requestNo = begin.requestNo();
            sink.turnStarted(new TurnStartedEvent(requestNo, begin.requestVersion(), content));
            if (begin.assistantReply() != null) {
                AssistantMessageView reply = begin.assistantReply();
                if ("FAILED".equals(reply.status())) {
                    sink.turnFailed(replayFailure(requestNo, reply));
                    return;
                }
                if (!"COMPLETED".equals(reply.status())) {
                    sink.turnFailed(new TurnFailedEvent(requestNo, "AI_INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
                    return;
                }
                emitCompletedReply(sink, customerId, conversationNo, reply);
                return;
            }
            RouteCheckChoicePayload previous = parseRouteCheckChoicePayload(begin.latestAssistantMessage());
            if (previous == null || !taskNo.equals(previous.taskNo())
                    || expectedRequestVersion != previous.requestVersion()
                    || !Objects.equals(previous.labelsByMode().get(checkMode), choiceLabel)) {
                completeText(sink, customerId, conversationNo, requestNo,
                        "这组核查方式选项已失效，请重新询问当前路线。", null);
            } else {
                continueCurrentRouteCheckMode(sink, customerId, conversationNo, requestNo,
                        begin.requestVersion(), taskNo, checkMode, previous);
            }
            sink.turnCompleted(new TurnCompletedEvent(requestNo));
        } catch (Exception e) {
            TurnFailedEvent failed = classify(requestNo, e);
            log.warn("AI 客服核查含义选择失败 requestNo={} code={} type={}",
                    requestNo, failed.code(), e.getClass().getSimpleName(), e);
            finishFailedTurn(sink, customerId, conversationNo, requestNo, failed);
        }
    }

    private void continueCurrentRouteCheckMode(AiTurnEventSink sink,
                                               long customerId,
                                               String conversationNo,
                                               String requestNo,
                                               long currentRequestVersion,
                                               String taskNo,
                                               String checkMode,
                                               RouteCheckChoicePayload previous) {
        CheckContext previousContext = previous.checkContext();
        // 选项绑定的路线必须与核查使用同一条 routeRef，否则地图会拒绝这次续接。
        String routeRef = previousContext != null && hasText(previousContext.routeRef())
                ? previousContext.routeRef() : latestPersistedRouteRef(customerId, conversationNo);
        if (routeRef == null) {
            completeText(sink, customerId, conversationNo, requestNo, CURRENT_ROUTE_UNAVAILABLE, null);
            return;
        }
        CurrentRouteCheckResponse result = unwrap(mapRouteAi.selectCurrentRouteCheckMode(
                new CurrentRouteCheckModeChoiceRequest(customerId, conversationNo, routeRef,
                        previous.requestVersion(), currentRequestVersion, taskNo, checkMode)),
                "selectCurrentRouteCheckMode");
        completeCurrentRouteCheckResult(sink, customerId, conversationNo, requestNo,
                currentRequestVersion, previous.targetName(), result,
                previousContext == null ? null
                        : previousContext.withRouteRef(routeRef, previousContext.routeShown()));
    }

    /** 已确认起终点仍然有效才生成路线；条件失效返回 null 由调用方给出可读反馈。 */
    private RouteCardDto createDefaultRoute(long customerId, String conversationNo,
                                            long conditionVersion) {
        try {
            return unwrap(mapRouteAi.defaultRoute(new DefaultRouteRequest(
                    customerId, conversationNo, conditionVersion)), "default");
        } catch (FeignException e) {
            if (hasAiErrorCode(e, "AI_ROUTE_CONTEXT_UNAVAILABLE")) {
                return null;
            }
            throw e;
        }
    }

    private void completeDefaultRoute(AiTurnEventSink sink,
                                      long customerId,
                                      String conversationNo,
                                      String requestNo,
                                      long conditionVersion) {
        RouteCardDto card = createDefaultRoute(customerId, conversationNo, conditionVersion);
        if (card == null) {
            completeText(sink, customerId, conversationNo, requestNo,
                    "路线条件已过期，请重新告诉我完整的起点和终点。", null);
            return;
        }
        completeRouteCard(sink, customerId, conversationNo, requestNo, card);
    }

    private void completeRouteCard(AiTurnEventSink sink,
                                   long customerId,
                                   String conversationNo,
                                   String requestNo,
                                   RouteCardDto card) {
        emitRouteCard(sink, customerId, conversationNo, requestNo, card, null, null, null);
    }

    /** 新生成路线的核查结论与路线卡同轮下发，避免把结论挂到乘客还没看到的路线。 */
    private void completeRouteCardWithCheck(AiTurnEventSink sink, long customerId,
                                            String conversationNo, String requestNo,
                                            RouteCardDto card, Map<String, Object> routeCheck,
                                            String textPrefix, String verdictText) {
        emitRouteCard(sink, customerId, conversationNo, requestNo, card, routeCheck,
                textPrefix, verdictText);
    }

    /** 本轮新建但尚未展示的路线先补发 route.card；地图缓存失效时降级为纯文本结论。 */
    private void resumeRouteCardWithCheck(AiTurnEventSink sink, long customerId,
                                          String conversationNo, String requestNo, String routeRef,
                                          Map<String, Object> routeCheck, String textPrefix,
                                          String verdictText) {
        RouteCardDto card = replayRouteCardDto(customerId, conversationNo, routeRef);
        if (card == null) {
            completeText(sink, customerId, conversationNo, requestNo, verdictText,
                    routeCheckPayloadJson(routeCheck));
            return;
        }
        emitRouteCard(sink, customerId, conversationNo, requestNo, card, routeCheck,
                textPrefix, verdictText);
    }

    private void emitRouteCard(AiTurnEventSink sink, long customerId, String conversationNo,
                               String requestNo, RouteCardDto card, Map<String, Object> routeCheck,
                               String textPrefix, String verdictText) {
        if (card == null) {
            throw new IllegalStateException("路线卡响应为空");
        }
        String text = (hasText(textPrefix) ? textPrefix : "") + buildRouteCardText(card)
                + (hasText(verdictText) ? verdictText : "");
        // passenger-service 只持久化路线卡摘要；完整折线只发送给本轮 SSE 客户端。
        String persistedPayload = toPersistedPayload(card, routeCheck);
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "ROUTE_CARD", text, persistedPayload)),
                "complete");
        sink.content("route.card", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "ROUTE_CARD", text,
                routeCardPayload(cardAsMap(card), routeCheck), done.createdAt())));
    }

    private Map<String, Object> routeCardPayload(Map<String, Object> card,
                                                 Map<String, Object> routeCheck) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeCard", card);
        if (routeCheck != null) {
            payload.put("routeCheck", routeCheck);
        }
        return payload;
    }

    private Map<String, Object> cardAsMap(RouteCardDto card) {
        return objectMapper.convertValue(card,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    private void completePlaceChoices(AiTurnEventSink sink, long customerId, String conversationNo,
                                      String requestNo, long conditionVersion, long requestVersion,
                                      RoutePreparationResponse prep) {
        completePlaceChoices(sink, customerId, conversationNo, requestNo,
                conditionVersion, requestVersion, prep, DEFAULT_PLACE_CHOICE_FLOW, null, null);
    }

    private void completePlaceChoices(AiTurnEventSink sink, long customerId, String conversationNo,
                                      String requestNo, long conditionVersion, long requestVersion,
                                      RoutePreparationResponse prep, String flow,
                                      String viaPlaceName, String viaPlaceRegion) {
        completePlaceChoices(sink, customerId, conversationNo, requestNo, conditionVersion,
                requestVersion, prep, flow, viaPlaceName, viaPlaceRegion, null);
    }

    private void completePlaceChoices(AiTurnEventSink sink, long customerId, String conversationNo,
                                      String requestNo, long conditionVersion, long requestVersion,
                                      RoutePreparationResponse prep, String flow,
                                      String viaPlaceName, String viaPlaceRegion,
                                      CheckContext context) {
        var choices = prep.placeChoices();
        if (choices == null || choices.choices() == null || choices.choices().isEmpty()) {
            throw new IllegalStateException("地点选项响应缺少候选");
        }
        String roleName = "ORIGIN".equals(choices.role()) ? "起点" : "终点";
        String text = "找到多个可能的" + roleName + "，请选择下方最符合的地点。";
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskNo", choices.taskNo());
        view.put("role", choices.role());
        view.put("choices", choices.choices());
        view.put("expiresAt", choices.expiresAt());
        view.put("conditionVersion", conditionVersion);
        view.put("requestVersion", requestVersion);
        view.put("flow", flow);
        if (hasText(viaPlaceName)) {
            view.put("viaPlaceName", viaPlaceName);
        }
        if (hasText(viaPlaceRegion)) {
            view.put("viaPlaceRegion", viaPlaceRegion);
        }
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of("placeChoices", view));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("地点选项摘要序列化失败", e);
        }
        sendChoiceMessage(sink, customerId, conversationNo, requestNo, "PLACE_CHOICES",
                "place.choices", text, payloadJson, context);
    }

    /** 上下文脱敏视图：只描述核查意图与地点身份，routeRef 仅服务端续接时使用。 */
    private static Map<String, Object> checkContextView(CheckContext context) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("intent", context.intent());
        if (hasText(context.placeName())) {
            view.put("placeName", context.placeName());
        }
        if (hasText(context.placeRegion())) {
            view.put("placeRegion", context.placeRegion());
        }
        if (hasText(context.checkMode())) {
            view.put("checkMode", context.checkMode());
        }
        if (hasText(context.routeRef())) {
            view.put("routeRef", context.routeRef());
        }
        view.put("routeShown", context.routeShown());
        if (context.previousRouteExpired()) {
            view.put("previousRouteExpired", true);
        }
        return view;
    }

    /** 服务端私有上下文只写入持久化载荷，客户端事件与历史接口都由剥离方法过滤。 */
    private String withCheckContext(String payloadJson, CheckContext context) {
        if (context == null) {
            return payloadJson;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> existing = parsePayloadJson(payloadJson);
        if (existing != null) {
            payload.putAll(existing);
        }
        payload.put(CHECK_CONTEXT_FIELD, checkContextView(context));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("核查上下文序列化失败", e);
        }
    }

    private Map<String, Object> clientPayload(String payloadJson) {
        Map<String, Object> payload = parsePayloadJson(payloadJson);
        if (payload == null
                || (!payload.containsKey(CHECK_CONTEXT_FIELD)
                    && !payload.containsKey(WEATHER_CONTEXT_FIELD))) {
            return payload;
        }
        Map<String, Object> clientView = new LinkedHashMap<>(payload);
        clientView.remove(CHECK_CONTEXT_FIELD);
        clientView.remove(WEATHER_CONTEXT_FIELD);
        return clientView.isEmpty() ? null : clientView;
    }

    private MessageView withoutInternalContext(MessageView message) {
        String payloadJson = strippedPayloadJson(message.payloadJson());
        if (payloadJson == null || payloadJson.equals(message.payloadJson())) {
            return message;
        }
        return new MessageView(message.messageNo(), message.requestNo(), message.clientMessageNo(),
                message.role(), message.messageType(), message.content(), payloadJson,
                message.sequenceNo(), message.status(), message.createdAt());
    }

    private AssistantMessageView withoutInternalContext(AssistantMessageView reply) {
        if (reply == null) {
            return null;
        }
        String payloadJson = strippedPayloadJson(reply.payloadJson());
        if (payloadJson == null || payloadJson.equals(reply.payloadJson())) {
            return reply;
        }
        return new AssistantMessageView(reply.messageNo(), reply.requestNo(), reply.role(),
                reply.messageType(), reply.content(), payloadJson, reply.sequenceNo(),
                reply.createdAt(), reply.status(), reply.failureCode(), reply.failureMessage());
    }

    /** 历史与状态接口不返回服务端私有上下文；无其余字段时按无载荷返回。 */
    private String strippedPayloadJson(String payloadJson) {
        Map<String, Object> clientView = clientPayload(payloadJson);
        if (clientView == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(clientView);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("客服消息载荷序列化失败", e);
        }
    }

    private CheckContext parseCheckContext(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String intent = node.path("intent").asText(null);
        String placeName = node.path("placeName").asText(null);
        if (!Set.of(CURRENT_ROUTE_CHECK, ROUTE_EXISTS).contains(intent) || !hasText(placeName)) {
            return null;
        }
        return new CheckContext(intent, placeName, node.path("placeRegion").asText(null),
                node.path("checkMode").asText(null), node.path("routeRef").asText(null),
                node.path("routeShown").asBoolean(false),
                node.path("previousRouteExpired").asBoolean(false));
    }

    /** 上一轮追问落库的核查上下文：本轮补齐端点后继续原核查。 */
    private CheckContext parsePendingCheckContext(AssistantMessageView message) {
        if (message == null || !"TEXT".equals(message.messageType())
                || !hasText(message.payloadJson())) {
            return null;
        }
        try {
            return parseCheckContext(objectMapper.readTree(message.payloadJson())
                    .path(CHECK_CONTEXT_FIELD));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 只有地图认识的显式动作才透传；UNSPECIFIED 与模型自造值交给地图按复合关系核查。 */
    private static String normalizeCheckMode(String checkMode) {
        if (!hasText(checkMode)) {
            return null;
        }
        String mode = checkMode.strip();
        return CHECK_MODES.contains(mode) ? mode : null;
    }

    private PlaceChoicePayload parsePlaceChoicePayload(AssistantMessageView message) {
        if (message == null || !"PLACE_CHOICES".equals(message.messageType())) return null;
        try {
            JsonNode payload = objectMapper.readTree(message.payloadJson());
            JsonNode root = payload.path("placeChoices");
            String taskNo = root.path("taskNo").asText(null);
            String flow = root.path("flow").asText(DEFAULT_PLACE_CHOICE_FLOW);
            String role = root.path("role").asText(null);
            long conditionVersion = root.path("conditionVersion").asLong(0);
            long requestVersion = root.path("requestVersion").asLong(0);
            String viaPlaceName = root.path("viaPlaceName").asText(null);
            String viaPlaceRegion = root.path("viaPlaceRegion").asText(null);
            CheckContext context = parseCheckContext(payload.path(CHECK_CONTEXT_FIELD));
            if (taskNo == null || requestVersion <= 0
                    || !Set.of(DEFAULT_PLACE_CHOICE_FLOW, CURRENT_ROUTE_CHECK, VIA_ROUTE_PLAN,
                    ROUTE_EXISTS).contains(flow)
                    && !PARTIAL_ENDPOINT_FLOW.equals(flow)
                    || (!CURRENT_ROUTE_CHECK.equals(flow) && conditionVersion <= 0)
                    || (VIA_ROUTE_PLAN.equals(flow) && !"WAYPOINT".equals(role)
                    && !hasText(viaPlaceName))
                    || (ROUTE_EXISTS.equals(flow) && context == null)) {
                return null;
            }
            Set<String> ids = new java.util.HashSet<>();
            root.path("choices").forEach(choice -> ids.add(choice.path("placeChoiceId").asText("")));
            return new PlaceChoicePayload(taskNo, conditionVersion, requestVersion, flow,
                    role, viaPlaceName, viaPlaceRegion, ids, context);
        } catch (Exception e) {
            return null;
        }
    }

    private RouteCheckChoicePayload parseRouteCheckChoicePayload(AssistantMessageView message) {
        if (message == null || !"ROUTE_CHECK_CHOICES".equals(message.messageType())) return null;
        try {
            JsonNode payload = objectMapper.readTree(message.payloadJson());
            JsonNode root = payload.path("routeCheckChoices");
            String taskNo = root.path("taskNo").asText(null);
            long requestVersion = root.path("requestVersion").asLong(0);
            String targetName = root.path("target").path("name").asText("所选地点");
            CheckContext context = parseCheckContext(payload.path(CHECK_CONTEXT_FIELD));
            if (taskNo == null || requestVersion <= 0
                    || (context != null && !CURRENT_ROUTE_CHECK.equals(context.intent()))) {
                return null;
            }
            Map<String, String> labels = new java.util.HashMap<>();
            root.path("choices").forEach(choice -> {
                String id = choice.path("choiceId").asText(null);
                String label = choice.path("label").asText(null);
                if (id != null && label != null) labels.put(id, label);
            });
            if (labels.size() < 2) return null;
            return new RouteCheckChoicePayload(taskNo, requestVersion, targetName, Map.copyOf(labels),
                    context);
        } catch (Exception e) {
            return null;
        }
    }

    private PartialEndpointPayload parsePartialEndpointPayload(AssistantMessageView message) {
        if (message == null || !"TEXT".equals(message.messageType())
                || message.payloadJson() == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(message.payloadJson()).path("partialEndpoint");
            long conditionVersion = root.path("conditionVersion").asLong(0);
            String followUpRequestNo = root.path("followUpRequestNo").asText(null);
            String knownRole = root.path("knownRole").asText(null);
            String missingRole = root.path("missingRole").asText(null);
            String knownPlaceName = root.path("knownPlaceName").asText(null);
            String intent = root.path("intent").asText(null);
            if (conditionVersion <= 0 || !hasText(followUpRequestNo)
                    || !Set.of("ORIGIN", "DESTINATION").contains(knownRole)
                    || !Set.of("ORIGIN", "DESTINATION").contains(missingRole)
                    || knownRole.equals(missingRole) || !hasText(knownPlaceName)
                    || !Set.of("DEFAULT_ROUTE", VIA_ROUTE_PLAN).contains(intent)) {
                return null;
            }
            return new PartialEndpointPayload(conditionVersion, followUpRequestNo,
                    knownRole, missingRole, knownPlaceName, intent,
                    root.path("viaPlaceName").asText(null),
                    root.path("viaPlaceRegion").asText(null));
        } catch (Exception e) {
            return null;
        }
    }

    /** 选择编号只用于接口关联；会话正文保存服务端展示摘要，不暴露内部 PC 编号。 */
    private static String selectedPlaceContent(String choiceName, String choiceCity) {
        String name = hasText(choiceName) ? choiceName.strip() : "所选地点";
        String city = hasText(choiceCity) ? choiceCity.strip() : null;
        return "已选择地点：" + name + (city == null ? "" : "（" + city + "）");
    }

    /** 跨页找回同一任务的已完成选项，兼容同键重放；不把客户端传入的名称当作可信正文。 */
    private String persistedPlaceChoiceContent(long customerId, String conversationNo,
                                               String taskNo, String placeChoiceId,
                                               long expectedRequestVersion) {
        Long beforeSequence = null;
        while (true) {
            AiMessageListResponse page = unwrap(passengerAi.listMessages(
                    conversationNo, customerId, MESSAGE_PAGE_SIZE, beforeSequence), "placeChoiceHistory");
            List<MessageView> messages = page.messages() == null ? List.of() : page.messages();
            for (int index = messages.size() - 1; index >= 0; index--) {
                MessageView message = messages.get(index);
                if (!"ASSISTANT".equals(message.role()) || !"PLACE_CHOICES".equals(message.messageType())
                        || !"COMPLETED".equals(message.status())) {
                    continue;
                }
                try {
                    JsonNode choices = objectMapper.readTree(message.payloadJson()).path("placeChoices");
                    if (!taskNo.equals(choices.path("taskNo").asText(null))
                            || expectedRequestVersion != choices.path("requestVersion").asLong(0)) {
                        continue;
                    }
                    for (JsonNode choice : choices.path("choices")) {
                        if (placeChoiceId.equals(choice.path("placeChoiceId").asText(null))
                                && hasText(choice.path("name").asText(null))) {
                            return selectedPlaceContent(choice.path("name").asText(),
                                    choice.path("city").asText(null));
                        }
                    }
                } catch (Exception ignored) {
                    // 旧版或损坏的消息不能授权产生地点选择正文。
                }
            }
            if (!page.hasMore() || messages.isEmpty()) return null;
            long nextBefore = messages.getFirst().sequenceNo();
            if (nextBefore <= 0 || (beforeSequence != null && nextBefore >= beforeSequence)) return null;
            beforeSequence = nextBefore;
        }
    }

    private void completeText(AiTurnEventSink sink, long customerId, String conversationNo,
                              String requestNo, String text, String payloadJson) {
        completeText(sink, customerId, conversationNo, requestNo, text, payloadJson, null);
    }

    /** 追问类文本把核查上下文写进持久化载荷，下发给客户端的载荷不含内部字段。 */
    private void completeText(AiTurnEventSink sink, long customerId, String conversationNo,
                              String requestNo, String text, String payloadJson,
                              CheckContext context) {
        String persistedPayload = withCheckContext(payloadJson, context);
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "TEXT", text, persistedPayload)), "complete");
        sink.content("answer.completed", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "TEXT", text,
                clientPayload(persistedPayload), done.createdAt())));
    }

    /** 选项类消息同样只把内部上下文留在服务端；客户端按事件类型读取公开选项。 */
    private void sendChoiceMessage(AiTurnEventSink sink, long customerId, String conversationNo,
                                   String requestNo, String messageType, String eventName,
                                   String text, String payloadJson, CheckContext context) {
        String persistedPayload = withCheckContext(payloadJson, context);
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, messageType, text, persistedPayload)),
                "complete");
        sink.content(eventName, new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", messageType, text,
                clientPayload(persistedPayload), done.createdAt())));
    }

    private boolean isConfirmationReply(String content, AiTurnBeginResponse begin) {
        AssistantMessageView latest = begin.latestAssistantMessage();
        return CONFIRMATION_REPLY.equals(content.strip())
                && latest != null
                && parseConfirmationPayload(latest.payloadJson()) != null;
    }

    private ConfirmationPayload parseConfirmationPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (!node.isObject() || !node.path(CONFIRMATION_MARKER).asBoolean(false)) {
                return null;
            }
            long conditionVersion = node.path("conditionVersion").asLong(0);
            if (conditionVersion <= 0) {
                return null;
            }
            return new ConfirmationPayload(conditionVersion);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 持久化摘要：routeRef + 两端名称 + 里程与预计时间，不含折线；核查结论同卷保存便于重放。 */
    private String toPersistedPayload(RouteCardDto card, Map<String, Object> routeCheck) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("routeRef", card.routeRef());
        summary.put("origin", Map.of("name", card.origin().name()));
        summary.put("destination", Map.of("name", card.destination().name()));
        if (card.via() != null && hasText(card.via().name())) {
            summary.put("via", Map.of("name", card.via().name()));
        }
        summary.put("route", Map.of(
                "coordinateSystem", card.route().coordinateSystem(),
                "distanceMeters", card.route().distanceMeters(),
                "durationSeconds", card.route().durationSeconds()));
        summary.put("generatedAt", card.generatedAt().toString());
        summary.put("expiresAt", card.expiresAt().toString());
        try {
            return objectMapper.writeValueAsString(routeCardPayload(summary, routeCheck));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("路线卡摘要序列化失败", e);
        }
    }

    private String buildRouteCardText(RouteCardDto card) {
        double kilometers = Math.round(card.route().distanceMeters() / 100.0) / 10.0;
        long minutes = (card.route().durationSeconds() + 59) / 60;
        String via = card.via() == null || !hasText(card.via().name())
                ? "" : "，途经" + card.via().name();
        return "已为您规划从" + card.origin().name() + "到" + card.destination().name()
                + "的路线" + via + "，全程约" + kilometers + "公里，预计行驶" + minutes + "分钟。";
    }

    private String buildClarification(ExtractEndpointsResponse extracted, RoutePreparationResponse prep) {
        String originPart = endpointClarification("起点", extracted.originName(), prep.originFeedback());
        String destinationPart = endpointClarification("终点", extracted.destinationName(), prep.destinationFeedback());
        return originPart + destinationPart + "请补充后我继续为您规划。";
    }

    private String endpointClarification(String label, String name, EndpointFeedbackView feedback) {
        if (UNIQUE.equals(feedback.status())) {
            return "";
        }
        return switch (feedback.status()) {
            case "NOT_FOUND" -> "没有找到「" + name + "」，";
            case "INVALID_MAP_DATA" -> "「" + name + "」的地点信息不完整，";
            default -> "「" + name + "」有多个可能地点" + candidatesClause(feedback) + "，";
        };
    }

    private String candidatesClause(EndpointFeedbackView feedback) {
        if (feedback.candidates() == null || feedback.candidates().isEmpty()) {
            return "";
        }
        String names = feedback.candidates().stream()
                .limit(5)
                .map(candidate -> candidate.city() == null
                        ? candidate.name()
                        : candidate.name() + "（" + candidate.city() + "）")
                .collect(Collectors.joining("、"));
        return "：" + names;
    }

    private ContentEvent contentEvent(AssistantMessageView view) {
        return new ContentEvent(view.requestNo(), new AssistantMessageEvent(
                view.messageNo(), view.requestNo(), view.role(), view.messageType(), view.content(),
                clientPayload(view.payloadJson()), view.createdAt()));
    }

    /** 有效期内从 map-service 恢复完整卡片；缓存或条件失效时只重放持久化摘要。 */
    private ContentEvent replayRouteCard(AssistantMessageView view, long customerId,
                                         String conversationNo) {
        Map<String, Object> stored = parsePayloadJson(view.payloadJson());
        Map<String, Object> summary = routeCardSummary(stored);
        Map<String, Object> routeCheck = storedRouteCheck(stored);
        String routeRef = routeRefOf(summary);
        if (routeRef != null) {
            RouteCardDto card = replayRouteCardDto(customerId, conversationNo, routeRef);
            if (card != null) {
                return routeCardContent(view, routeCardPayload(cardAsMap(card), routeCheck));
            }
        }
        Map<String, Object> expiredSummary = new LinkedHashMap<>(summary);
        expiredSummary.remove("polyline");
        Object route = expiredSummary.get("route");
        if (route instanceof Map<?, ?> routeMap) {
            Map<String, Object> withoutPolyline = new LinkedHashMap<>();
            routeMap.forEach((key, value) -> {
                if (key instanceof String name && !"polyline".equals(name)) {
                    withoutPolyline.put(name, value);
                }
            });
            expiredSummary.put("route", withoutPolyline);
        }
        expiredSummary.put("expired", true);
        return routeCardContent(view, routeCardPayload(expiredSummary, routeCheck));
    }

    /** 路线卡折线只保留在地图短时缓存；缓存失效或卡片不完整时返回 null 由调用方降级。 */
    private RouteCardDto replayRouteCardDto(long customerId, String conversationNo,
                                            String routeRef) {
        try {
            ResponseVo<RouteReplayResponse> response = mapRouteAi.replay(
                    new RouteReplayRequest(customerId, conversationNo, routeRef));
            RouteCardDto card = response != null && Objects.equals(response.getCode(), 200)
                    && response.getData() != null ? response.getData().routeCard() : null;
            if (card != null && routeRef.equals(card.routeRef()) && card.expiresAt() != null
                    && card.expiresAt().isAfter(Instant.now()) && card.route() != null
                    && card.route().polyline() != null && !card.route().polyline().isEmpty()) {
                return card;
            }
        } catch (RuntimeException e) {
            // 重放已成功落库的消息不依赖地图服务可用；只降级为历史展示摘要。
            log.warn("客服路线卡重放缓存读取失败 conversationNo={} type={}",
                    conversationNo, e.getClass().getSimpleName());
        }
        return null;
    }

    private Map<String, Object> storedRouteCheck(Map<String, Object> stored) {
        if (stored == null || !(stored.get("routeCheck") instanceof Map<?, ?> check)) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        check.forEach((key, value) -> {
            if (key instanceof String name) {
                view.put(name, value);
            }
        });
        return view.isEmpty() ? null : view;
    }

    private static String routeRefOf(Map<String, Object> summary) {
        Object value = summary.get("routeRef");
        return value instanceof String ref && !ref.isBlank() ? ref.strip() : null;
    }

    private Map<String, Object> routeCardSummary(Map<String, Object> stored) {
        if (stored == null) {
            return Map.of();
        }
        Object nested = stored.get("routeCard");
        if (nested instanceof Map<?, ?> card) {
            Map<String, Object> summary = new LinkedHashMap<>();
            card.forEach((key, value) -> {
                if (key instanceof String name) {
                    summary.put(name, value);
                }
            });
            return summary;
        }
        // 兼容旧版直接把 routeRef、route 放在 payload 根节点的历史消息。
        return stored;
    }

    /**
     * 从服务端持久化的会话消息倒序寻找最新路线卡。历史可以跨页，但只接受已完成的客服路线卡；
     * routeRef 随后仍由 map-service 复验乘客、会话、路线条件和有效期。
     */
    private String latestPersistedRouteRef(long customerId, String conversationNo) {
        Long beforeSequence = null;
        while (true) {
            AiMessageListResponse page = unwrap(passengerAi.listMessages(
                    conversationNo, customerId, MESSAGE_PAGE_SIZE, beforeSequence), "latestRouteCard");
            List<MessageView> messages = page.messages() == null ? List.of() : page.messages();
            for (int index = messages.size() - 1; index >= 0; index--) {
                MessageView message = messages.get(index);
                if (!"ASSISTANT".equals(message.role()) || !"ROUTE_CARD".equals(message.messageType())
                        || !"COMPLETED".equals(message.status())) {
                    continue;
                }
                String routeRef = routeRefFromPayload(message.payloadJson());
                if (routeRef != null) {
                    return routeRef;
                }
            }
            if (!page.hasMore() || messages.isEmpty()) {
                return null;
            }
            long nextBefore = messages.getFirst().sequenceNo();
            if (nextBefore <= 0 || (beforeSequence != null && nextBefore >= beforeSequence)) {
                return null;
            }
            beforeSequence = nextBefore;
        }
    }

    private String routeRefFromPayload(String payloadJson) {
        return routeRefOf(routeCardSummary(parsePayloadJson(payloadJson)));
    }

    private static String detailOrFallback(String detail, String fallback) {
        return hasText(detail) ? detail.strip() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ContentEvent routeCardContent(AssistantMessageView view, Map<String, Object> payload) {
        return new ContentEvent(view.requestNo(), new AssistantMessageEvent(
                view.messageNo(), view.requestNo(), view.role(), view.messageType(), view.content(),
                payload, view.createdAt()));
    }

    private Map<String, Object> parsePayloadJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payloadJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String eventNameFor(String messageType) {
        return switch (messageType) {
            case "ROUTE_CARD" -> "route.card";
            case "PLACE_CHOICES" -> "place.choices";
            case "ROUTE_CHECK_CHOICES" -> "route-check.choices";
            default -> "answer.completed";
        };
    }

    private TurnFailedEvent replayFailure(String requestNo, AssistantMessageView reply) {
        String code = reply.failureCode();
        String message = reply.failureMessage();
        return new TurnFailedEvent(requestNo,
                code == null || code.isBlank() ? "AI_INTERNAL_ERROR" : code,
                message == null || message.isBlank() ? "服务暂时不可用，请稍后重试" : message);
    }

    private <T> T unwrap(ResponseVo<T> response, String action) {
        if (response == null || !Objects.equals(response.getCode(), 200) || response.getData() == null) {
            throw new BizErrorException(502, "客服服务" + action + "调用失败");
        }
        return response.getData();
    }

    private TurnFailedEvent classify(String requestNo, Throwable e) {
        if (e instanceof FeignException feign) {
            String upstreamCode = aiErrorCode(feign);
            if (upstreamCode != null) {
                return switch (upstreamCode) {
                    case "AI_POI_LOOKUP_FAILED" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "地点查询暂时失败，请稍后重试");
                    case "AI_ROUTE_NOT_FOUND" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "当前起终点未找到可达路线，请更换地点后重试");
                    case "AI_ROUTE_UNVERIFIABLE" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "已找到驾车路线，但无法确认从所选收费站按正确方向通行；请换个明确的高速出入口或普通地点重试");
                    case "AI_MAP_UNAVAILABLE" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "地图算路暂时不可用，请稍后重试");
                    case "AI_WEATHER_UNAVAILABLE" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "天气服务暂时不可用，请稍后重试");
                    case "AI_PROVIDER_TIMEOUT" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "客服理解请求超时，请稍后重试");
                    case "AI_PROVIDER_UNAVAILABLE" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "客服暂时没能处理这条消息，请稍后重试");
                    case "AI_PROVIDER_BUSY" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "客服当前请求较多，请稍后重试");
                    case "AI_PROVIDER_INVALID_RESPONSE" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "客服暂时没能理解这条消息，请稍后重试");
                    case "AI_INTERNAL_AUTH_FAILED" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "客服内部服务配置异常，请稍后重试");
                    case "AI_REQUEST_EXPIRED" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "本轮处理已超时，请重新发送消息");
                    case "AI_REQUEST_PROCESSING" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "这条消息仍在处理中，请稍后查看结果");
                    case "AI_IDEMPOTENCY_CONFLICT" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "同一请求标识对应的消息内容不一致，请重新发送");
                    default -> classifyByStatus(requestNo, feign.status());
                };
            }
            return classifyByStatus(requestNo, feign.status());
        }
        return new TurnFailedEvent(requestNo, "AI_INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
    }

    private String aiErrorCode(FeignException feign) {
        for (Map.Entry<String, java.util.Collection<String>> header : feign.responseHeaders().entrySet()) {
            if ("X-Ai-Error-Code".equalsIgnoreCase(header.getKey()) && header.getValue() != null) {
                return header.getValue().stream().findFirst().orElse(null);
            }
        }
        return null;
    }

    private boolean hasAiErrorCode(FeignException feign, String expected) {
        return expected.equals(aiErrorCode(feign));
    }

    private TurnFailedEvent classifyByStatus(String requestNo, int status) {
        return switch (status) {
            case 404 -> new TurnFailedEvent(requestNo, "AI_CONVERSATION_NOT_FOUND", "会话不存在或已失效");
            case 409 -> new TurnFailedEvent(requestNo, "AI_REQUEST_IN_PROGRESS", "同会话另有请求进行中，请稍后重试");
            case 504 -> new TurnFailedEvent(requestNo, "AI_MAP_TIMEOUT", "地图服务暂时不可用，请稍后重试");
            default -> new TurnFailedEvent(requestNo, "AI_INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
        };
    }

    private AiTurnFailResponse failQuietly(long customerId, String conversationNo,
                                           String requestNo, TurnFailedEvent failed) {
        try {
            return unwrap(passengerAi.fail(conversationNo, requestNo,
                    new AiTurnFailRequest(customerId, failed.code(), failed.message())), "fail");
        } catch (Exception ignored) {
            log.warn("AI 客服轮次失败消息落库失败 requestNo={}", requestNo);
            return null;
        }
    }

    /** 普通异常先与数据库最终状态对账；即使失败落库本身抛出 Error，也尽量发送终止事件。 */
    private void finishFailedTurn(AiTurnEventSink sink, long customerId, String conversationNo,
                                  String requestNo, TurnFailedEvent failed) {
        AiTurnFailResponse stored = null;
        try {
            if (requestNo != null) {
                stored = failQuietly(customerId, conversationNo, requestNo, failed);
            }
        } finally {
            AssistantMessageView reply = stored == null ? null : stored.assistantReply();
            if (reply != null && "COMPLETED".equals(reply.status())) {
                emitCompletedReply(sink, customerId, conversationNo, reply);
            } else if (reply != null && "FAILED".equals(reply.status())) {
                sink.turnFailed(replayFailure(requestNo, reply));
            } else {
                sink.turnFailed(failed);
            }
        }
    }

    private void emitCompletedReply(AiTurnEventSink sink, long customerId,
                                    String conversationNo, AssistantMessageView reply) {
        sink.content(eventNameFor(reply.messageType()),
                "ROUTE_CARD".equals(reply.messageType())
                        ? replayRouteCard(reply, customerId, conversationNo)
                        : contentEvent(reply));
        sink.turnCompleted(new TurnCompletedEvent(reply.requestNo()));
    }

    private record ConfirmationPayload(long conditionVersion) {
    }

    /**
     * 服务端核查上下文：随追问或选项消息落库，用于在接下来的轮次恢复核查状态。
     * routeShown 表示被核查的路线是否已经展示给乘客；previousRouteExpired 表示旧路线快照已失效。
     */
    private record CheckContext(String intent, String placeName, String placeRegion,
                                String checkMode, String routeRef, boolean routeShown,
                                boolean previousRouteExpired) {

        CheckContext withRouteRef(String newRouteRef, boolean newRouteShown) {
            return new CheckContext(intent, placeName, placeRegion, checkMode, newRouteRef,
                    newRouteShown, previousRouteExpired);
        }

        CheckContext asPreviousRouteExpired() {
            return new CheckContext(intent, placeName, placeRegion, checkMode, routeRef,
                    routeShown, true);
        }
    }

    private record PlaceChoicePayload(String taskNo, long conditionVersion,
                                      long requestVersion, String flow, String role,
                                      String viaPlaceName, String viaPlaceRegion,
                                      Set<String> choiceIds, CheckContext checkContext) {
    }

    private record RouteCheckChoicePayload(String taskNo, long requestVersion,
                                           String targetName, Map<String, String> labelsByMode,
                                           CheckContext checkContext) {
    }

    private record PartialEndpointPayload(long conditionVersion, String followUpRequestNo,
                                          String knownRole, String missingRole,
                                          String knownPlaceName, String intent,
                                          String viaPlaceName, String viaPlaceRegion) {
    }
}
