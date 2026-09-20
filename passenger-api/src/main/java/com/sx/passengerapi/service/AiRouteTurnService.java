package com.sx.passengerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.MapRouteAiClient;
import com.sx.passengerapi.client.PassengerAiConversationClient;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse;
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
 * AI 客服路线轮次编排：确认两端并生成默认路线卡，以及核查当前路线是否经过指定地点。
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
    static final String NEEDS_PLACE_CHOICE = "NEEDS_PLACE_CHOICE";
    static final String NEEDS_CHECK_MODE_CHOICE = "NEEDS_CHECK_MODE_CHOICE";
    static final String UNIQUE = "UNIQUE";
    static final String CURRENT_ROUTE_CHECK = "CURRENT_ROUTE_CHECK";

    private static final String DEFAULT_PLACE_CHOICE_FLOW = "ROUTE_PREPARATION";
    private static final int MESSAGE_PAGE_SIZE = 50;

    private static final String ASK_FOR_ENDPOINTS =
            "请告诉我起点和终点，例如：从德清高速路口到西溪湿地。";
    private static final String CONFIRMATION_NOT_ACCEPTED =
            "请回复「对」确认，或直接告诉我需要修改的地点。";
    private static final String CONFIRMATION_EXPIRED =
            "之前的起终点信息已失效，请重新告诉我完整的起点和终点。";
    private static final String ASK_FOR_CHECK_PLACE =
            "请告诉我要核查的收费站、服务区或加油站名称。";
    private static final String CURRENT_ROUTE_UNAVAILABLE =
            "当前会话没有可核查的有效路线，请先规划一条路线。";

    private final PassengerAiConversationClient passengerAi;
    private final MapRouteAiClient mapRouteAi;
    private final ObjectMapper objectMapper;

    public AiRouteTurnService(PassengerAiConversationClient passengerAi,
                              MapRouteAiClient mapRouteAi,
                              ObjectMapper objectMapper) {
        this.passengerAi = passengerAi;
        this.mapRouteAi = mapRouteAi;
        this.objectMapper = objectMapper;
    }

    /** 创建 AI 客服会话；幂等键重放由 passenger-service 保证。 */
    public AiConversationCreateResponse createConversation(long customerId, String idempotencyKey) {
        return unwrap(passengerAi.create(new AiConversationCreateRequest(customerId, idempotencyKey)), "create");
    }

    /** 分页读取历史消息；归属校验在下游，跨乘客访问按会话不存在处理。 */
    public AiMessageListResponse listMessages(long customerId, String conversationNo,
                                              Integer limit, Long beforeSequence) {
        return unwrap(passengerAi.listMessages(conversationNo, customerId, limit, beforeSequence), "listMessages");
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

            handleFreshText(sink, customerId, conversationNo, requestNo, begin.requestVersion(), content);
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
                                 String requestNo, long conditionVersion, String content) {
        ExtractEndpointsResponse extracted = unwrap(mapRouteAi.extract(
                new ExtractEndpointsRequest(content)), "extract");
        if (CURRENT_ROUTE_CHECK.equals(extracted.intent())) {
            handleCurrentRouteCheck(sink, customerId, conversationNo, requestNo,
                    conditionVersion, content, extracted);
            return;
        }
        if (extracted.originName() == null || extracted.destinationName() == null) {
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

    /** 当前路线只能取自本会话已持久化的最新路线卡，模型不能提交 routeRef。 */
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
            completeText(sink, customerId, conversationNo, requestNo, CURRENT_ROUTE_UNAVAILABLE, null);
            return;
        }
        CurrentRouteCheckResponse result = unwrap(mapRouteAi.checkCurrentRoute(
                new CurrentRouteCheckRequest(customerId, conversationNo, routeRef, requestVersion,
                        extracted.checkPlaceName(), extracted.checkPlaceRegion(),
                        extracted.checkMode(), content)),
                "checkCurrentRoute");
        completeCurrentRouteCheckResult(sink, customerId, conversationNo, requestNo,
                requestVersion, extracted.checkPlaceName(), result);
    }

    private void completeCurrentRouteCheckResult(AiTurnEventSink sink, long customerId,
                                                 String conversationNo, String requestNo,
                                                 long requestVersion, String requestedPlaceName,
                                                 CurrentRouteCheckResponse result) {
        switch (result.status()) {
            case "CHECKED" -> completeText(sink, customerId, conversationNo, requestNo,
                    currentRouteVerdictText(result, requestedPlaceName), null);
            case NEEDS_PLACE_CHOICE -> completeCurrentRoutePlaceChoices(sink, customerId,
                    conversationNo, requestNo, requestVersion, requestedPlaceName, result);
            case NEEDS_CHECK_MODE_CHOICE -> completeCurrentRouteModeChoices(sink, customerId,
                    conversationNo, requestNo, requestVersion, requestedPlaceName, result);
            case "PLACE_NOT_FOUND" -> completeText(sink, customerId, conversationNo, requestNo,
                    "没有找到「" + requestedPlaceName + "」，请补充城市或更具体的名称。", null);
            case "PLACE_UNVERIFIABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    "暂时无法核查「" + requestedPlaceName + "」：" + detailOrFallback(
                            result.detail(), "地图地点信息不足") + "。", null);
            case "CONTEXT_UNAVAILABLE" -> completeText(sink, customerId, conversationNo, requestNo,
                    CURRENT_ROUTE_UNAVAILABLE, null);
            default -> throw new IllegalStateException("未知的当前路线核查状态: " + result.status());
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
        String detail = detailOrFallback(result.detail(), "当前路线缺少足够的地图证据");
        return switch (result.verdict()) {
            case "PASSED" -> "根据当前路线的地图信息，这条路线会" + relation + "（" + detail + "）。";
            case "NOT_PASSED" -> "根据当前路线的地图信息，这条路线不会" + relation + "（" + detail + "）。";
            case "UNVERIFIABLE" -> "暂时无法确认当前路线是否会" + relation + "：" + detail + "。";
            case "CONFLICT" -> semanticConflictText(result.reason(), detail, placeName);
            default -> throw new IllegalStateException("未知的当前路线核查结论: " + result.verdict());
        };
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
                                                  CurrentRouteCheckResponse result) {
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
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "ROUTE_CHECK_CHOICES", text, payloadJson)), "complete");
        sink.content("route-check.choices", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "ROUTE_CHECK_CHOICES", text,
                parsePayloadJson(payloadJson), done.createdAt())));
    }

    /** 当前路线地点选项沿用既有 PLACE_CHOICES 事件，并写入 flow 供选择轮次安全分流。 */
    private void completeCurrentRoutePlaceChoices(AiTurnEventSink sink, long customerId,
                                                  String conversationNo, String requestNo,
                                                  long requestVersion, String requestedPlaceName,
                                                  CurrentRouteCheckResponse result) {
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
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "PLACE_CHOICES", text, payloadJson)), "complete");
        sink.content("place.choices", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "PLACE_CHOICES", text,
                parsePayloadJson(payloadJson), done.createdAt())));
    }

    /** 选项点击独立成一轮；旧任务、旧版本或越权选项均不能恢复地点身份。 */
    public void selectPlaceChoice(AiTurnEventSink sink, long customerId, String conversationNo,
                                  String taskNo, String placeChoiceId, long expectedRequestVersion,
                                  String choiceName, String choiceCity, String idempotencyKey) {
        String requestNo = null;
        String content = selectedPlaceContent(choiceName, choiceCity);
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
                sink.content(eventNameFor(reply.messageType()), contentEvent(reply));
                sink.turnCompleted(new TurnCompletedEvent(requestNo));
                return;
            }
            PlaceChoicePayload previous = parsePlaceChoicePayload(begin.latestAssistantMessage());
            if (previous == null || !taskNo.equals(previous.taskNo())
                    || expectedRequestVersion != previous.requestVersion()
                    || !previous.choiceIds().contains(placeChoiceId)) {
                String expiredText = previous != null && CURRENT_ROUTE_CHECK.equals(previous.flow())
                        ? "这组核查地点选项已失效，请重新发起当前路线核查。"
                        : "这组选项已失效，请重新告诉我起点和终点。";
                completeText(sink, customerId, conversationNo, requestNo,
                        expiredText, null);
            } else if (CURRENT_ROUTE_CHECK.equals(previous.flow())) {
                continueCurrentRouteCheckPlaceChoice(sink, customerId, conversationNo, requestNo,
                        begin.requestVersion(), taskNo, placeChoiceId, previous);
            } else {
                RoutePreparationResponse prep = unwrap(mapRouteAi.selectPlace(new RoutePlaceChoiceRequest(
                        customerId, conversationNo, taskNo, placeChoiceId,
                        previous.conditionVersion(), previous.requestVersion(), begin.requestVersion())), "selectPlace");
                if (READY_FOR_ROUTE.equals(prep.status())) {
                    completeDefaultRoute(sink, customerId, conversationNo, requestNo,
                            previous.conditionVersion());
                } else if (NEEDS_PLACE_CHOICE.equals(prep.status())) {
                    completePlaceChoices(sink, customerId, conversationNo, requestNo,
                            previous.conditionVersion(), begin.requestVersion(), prep);
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

    private void continueCurrentRouteCheckPlaceChoice(AiTurnEventSink sink, long customerId,
                                                      String conversationNo, String requestNo,
                                                      long currentRequestVersion, String taskNo,
                                                      String placeChoiceId,
                                                      PlaceChoicePayload previous) {
        String routeRef = latestPersistedRouteRef(customerId, conversationNo);
        if (routeRef == null) {
            completeText(sink, customerId, conversationNo, requestNo, CURRENT_ROUTE_UNAVAILABLE, null);
            return;
        }
        CurrentRouteCheckResponse result = unwrap(mapRouteAi.selectCurrentRouteCheckPlace(
                new CurrentRouteCheckPlaceChoiceRequest(customerId, conversationNo, routeRef,
                        previous.requestVersion(), currentRequestVersion, taskNo, placeChoiceId)),
                "selectCurrentRouteCheckPlace");
        completeCurrentRouteCheckResult(sink, customerId, conversationNo, requestNo,
                currentRequestVersion, "所选地点", result);
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
                sink.content(eventNameFor(reply.messageType()), contentEvent(reply));
                sink.turnCompleted(new TurnCompletedEvent(requestNo));
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
        String routeRef = latestPersistedRouteRef(customerId, conversationNo);
        if (routeRef == null) {
            completeText(sink, customerId, conversationNo, requestNo, CURRENT_ROUTE_UNAVAILABLE, null);
            return;
        }
        CurrentRouteCheckResponse result = unwrap(mapRouteAi.selectCurrentRouteCheckMode(
                new CurrentRouteCheckModeChoiceRequest(customerId, conversationNo, routeRef,
                        previous.requestVersion(), currentRequestVersion, taskNo, checkMode)),
                "selectCurrentRouteCheckMode");
        completeCurrentRouteCheckResult(sink, customerId, conversationNo, requestNo,
                currentRequestVersion, previous.targetName(), result);
    }

    private void completeDefaultRoute(AiTurnEventSink sink,
                                      long customerId,
                                      String conversationNo,
                                      String requestNo,
                                      long conditionVersion) {
        RouteCardDto card;
        try {
            card = unwrap(mapRouteAi.defaultRoute(new DefaultRouteRequest(
                    customerId, conversationNo, conditionVersion)), "default");
        } catch (FeignException e) {
            if (hasAiErrorCode(e, "AI_ROUTE_CONTEXT_UNAVAILABLE")) {
                completeText(sink, customerId, conversationNo, requestNo,
                        "路线条件已过期，请重新告诉我完整的起点和终点。", null);
                return;
            }
            throw e;
        }
        String cardText = buildRouteCardText(card);
        // passenger-service 只持久化路线卡摘要；完整折线只发送给本轮 SSE 客户端。
        String persistedPayload = toPersistedPayload(card);
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "ROUTE_CARD", cardText, persistedPayload)),
                "complete");
        sink.content("route.card", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "ROUTE_CARD", cardText,
                Map.of("routeCard", objectMapper.convertValue(card, Map.class)), done.createdAt())));
    }

    private void completePlaceChoices(AiTurnEventSink sink, long customerId, String conversationNo,
                                      String requestNo, long conditionVersion, long requestVersion,
                                      RoutePreparationResponse prep) {
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
        view.put("flow", DEFAULT_PLACE_CHOICE_FLOW);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of("placeChoices", view));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("地点选项摘要序列化失败", e);
        }
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "PLACE_CHOICES", text, payloadJson)), "complete");
        sink.content("place.choices", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "PLACE_CHOICES", text,
                parsePayloadJson(payloadJson), done.createdAt())));
    }

    private PlaceChoicePayload parsePlaceChoicePayload(AssistantMessageView message) {
        if (message == null || !"PLACE_CHOICES".equals(message.messageType())) return null;
        try {
            JsonNode root = objectMapper.readTree(message.payloadJson()).path("placeChoices");
            String taskNo = root.path("taskNo").asText(null);
            String flow = root.path("flow").asText(DEFAULT_PLACE_CHOICE_FLOW);
            long conditionVersion = root.path("conditionVersion").asLong(0);
            long requestVersion = root.path("requestVersion").asLong(0);
            if (taskNo == null || requestVersion <= 0
                    || !Set.of(DEFAULT_PLACE_CHOICE_FLOW, CURRENT_ROUTE_CHECK).contains(flow)
                    || (DEFAULT_PLACE_CHOICE_FLOW.equals(flow) && conditionVersion <= 0)) {
                return null;
            }
            Set<String> ids = new java.util.HashSet<>();
            root.path("choices").forEach(choice -> ids.add(choice.path("placeChoiceId").asText("")));
            return new PlaceChoicePayload(taskNo, conditionVersion, requestVersion, flow, ids);
        } catch (Exception e) {
            return null;
        }
    }

    private RouteCheckChoicePayload parseRouteCheckChoicePayload(AssistantMessageView message) {
        if (message == null || !"ROUTE_CHECK_CHOICES".equals(message.messageType())) return null;
        try {
            JsonNode root = objectMapper.readTree(message.payloadJson()).path("routeCheckChoices");
            String taskNo = root.path("taskNo").asText(null);
            long requestVersion = root.path("requestVersion").asLong(0);
            String targetName = root.path("target").path("name").asText("所选地点");
            if (taskNo == null || requestVersion <= 0) return null;
            Map<String, String> labels = new java.util.HashMap<>();
            root.path("choices").forEach(choice -> {
                String id = choice.path("choiceId").asText(null);
                String label = choice.path("label").asText(null);
                if (id != null && label != null) labels.put(id, label);
            });
            if (labels.size() < 2) return null;
            return new RouteCheckChoicePayload(taskNo, requestVersion, targetName, Map.copyOf(labels));
        } catch (Exception e) {
            return null;
        }
    }

    /** 选择编号只用于接口关联；会话正文保存乘客实际看到的地点，不暴露内部 PC 编号。 */
    private static String selectedPlaceContent(String choiceName, String choiceCity) {
        String name = hasText(choiceName) ? choiceName.strip() : "所选地点";
        String city = hasText(choiceCity) ? choiceCity.strip() : null;
        return "已选择地点：" + name + (city == null ? "" : "（" + city + "）");
    }

    private void completeText(AiTurnEventSink sink, long customerId, String conversationNo,
                              String requestNo, String text, String payloadJson) {
        AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                new AiTurnCompleteRequest(customerId, "TEXT", text, payloadJson)), "complete");
        sink.content("answer.completed", new ContentEvent(requestNo, new AssistantMessageEvent(
                done.messageNo(), requestNo, "ASSISTANT", "TEXT", text,
                parsePayloadJson(payloadJson), done.createdAt())));
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

    /** 持久化摘要：routeRef + 两端名称 + 里程与预计时间，不含折线。 */
    private String toPersistedPayload(RouteCardDto card) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("routeRef", card.routeRef());
        summary.put("origin", Map.of("name", card.origin().name()));
        summary.put("destination", Map.of("name", card.destination().name()));
        summary.put("route", Map.of(
                "coordinateSystem", card.route().coordinateSystem(),
                "distanceMeters", card.route().distanceMeters(),
                "durationSeconds", card.route().durationSeconds()));
        summary.put("generatedAt", card.generatedAt().toString());
        summary.put("expiresAt", card.expiresAt().toString());
        try {
            return objectMapper.writeValueAsString(Map.of("routeCard", summary));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("路线卡摘要序列化失败", e);
        }
    }

    private String buildRouteCardText(RouteCardDto card) {
        double kilometers = Math.round(card.route().distanceMeters() / 100.0) / 10.0;
        long minutes = (card.route().durationSeconds() + 59) / 60;
        return "已为您规划从" + card.origin().name() + "到" + card.destination().name()
                + "的路线，全程约" + kilometers + "公里，预计行驶" + minutes + "分钟。";
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
                parsePayloadJson(view.payloadJson()), view.createdAt()));
    }

    /** 有效期内从 map-service 恢复完整卡片；缓存或条件失效时只重放持久化摘要。 */
    private ContentEvent replayRouteCard(AssistantMessageView view, long customerId,
                                         String conversationNo) {
        Map<String, Object> stored = parsePayloadJson(view.payloadJson());
        Map<String, Object> summary = routeCardSummary(stored);
        Object routeRefValue = summary.get("routeRef");
        String routeRef = routeRefValue instanceof String ref && !ref.isBlank() ? ref : null;
        if (routeRef != null) {
            try {
                ResponseVo<RouteReplayResponse> response = mapRouteAi.replay(
                        new RouteReplayRequest(customerId, conversationNo, routeRef));
                RouteCardDto card = response != null && Objects.equals(response.getCode(), 200)
                        && response.getData() != null ? response.getData().routeCard() : null;
                if (card != null && routeRef.equals(card.routeRef()) && card.expiresAt() != null
                        && card.expiresAt().isAfter(Instant.now()) && card.route() != null
                        && card.route().polyline() != null && !card.route().polyline().isEmpty()) {
                    return routeCardContent(view, objectMapper.convertValue(card, Map.class));
                }
            } catch (RuntimeException e) {
                // 重放已成功落库的消息不依赖地图服务可用；只降级为历史展示摘要。
                log.warn("客服路线卡重放缓存读取失败 requestNo={} type={}",
                        view.requestNo(), e.getClass().getSimpleName());
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
        return routeCardContent(view, expiredSummary);
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
        Object value = routeCardSummary(parsePayloadJson(payloadJson)).get("routeRef");
        return value instanceof String routeRef && !routeRef.isBlank() ? routeRef.strip() : null;
    }

    private static String detailOrFallback(String detail, String fallback) {
        return hasText(detail) ? detail.strip() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ContentEvent routeCardContent(AssistantMessageView view, Map<String, Object> card) {
        return new ContentEvent(view.requestNo(), new AssistantMessageEvent(
                view.messageNo(), view.requestNo(), view.role(), view.messageType(), view.content(),
                Map.of("routeCard", card), view.createdAt()));
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
                    case "AI_MAP_UNAVAILABLE" -> new TurnFailedEvent(requestNo, upstreamCode,
                            "地图算路暂时不可用，请稍后重试");
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

    private record PlaceChoicePayload(String taskNo, long conditionVersion,
                                      long requestVersion, String flow, Set<String> choiceIds) {
    }

    private record RouteCheckChoicePayload(String taskNo, long requestVersion,
                                           String targetName, Map<String, String> labelsByMode) {
    }
}
