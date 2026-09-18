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
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AssistantMessageView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.DefaultRouteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.EndpointFeedbackView;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteCardDto;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationResponse;
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

import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI 客服路线轮次编排：确认两端 → 默认路线卡的主干闭环。
 *
 * 地图地点、路线与分钟数只来自 map-service 确定性能力，模型只负责提取地点名称；
 * 本类按结构化状态决定分支，不依赖模型文本作为业务权威。事件顺序遵循 API 契约：
 * turn.started → answer.completed / route.card → turn.completed，失败发送 turn.failed。
 */
@Service
public class AiRouteTurnService {

    private static final Logger log = LoggerFactory.getLogger(AiRouteTurnService.class);

    static final String CONFIRMATION_MARKER = "endpointConfirmation";
    static final String CONFIRMATION_REPLY = "对";
    static final String CONFIRMED = "CONFIRMED";
    static final String NOT_ACCEPTED = "NOT_ACCEPTED";
    static final String READY_FOR_CONFIRMATION = "READY_FOR_CONFIRMATION";
    static final String UNIQUE = "UNIQUE";

    private static final String ASK_FOR_ENDPOINTS =
            "请告诉我起点和终点，例如：从德清高速路口到西溪湿地。";
    private static final String CONFIRMATION_NOT_ACCEPTED =
            "请回复「对」确认，或直接告诉我需要修改的地点。";
    private static final String CONFIRMATION_EXPIRED =
            "之前的起终点信息已失效，请重新告诉我完整的起点和终点。";

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
                sink.content(eventNameFor(reply.messageType()),
                        "ROUTE_CARD".equals(reply.messageType())
                                ? replayRouteCard(reply, customerId, conversationNo)
                                : contentEvent(reply));
                sink.turnCompleted(new TurnCompletedEvent(requestNo));
                return;
            }

            if (isConfirmationReply(content, begin)) {
                handleConfirmation(sink, customerId, conversationNo, requestNo, begin);
                sink.turnCompleted(new TurnCompletedEvent(requestNo));
                return;
            }

            handleFreshText(sink, customerId, conversationNo, requestNo, begin.requestVersion(), content);
            sink.turnCompleted(new TurnCompletedEvent(requestNo));
        } catch (Throwable e) {
            TurnFailedEvent failed = classify(requestNo, e);
            log.warn("AI 客服轮次失败 requestNo={} code={} type={}",
                    requestNo, failed.code(), e.getClass().getSimpleName(), e);
            if (requestNo != null) {
                failQuietly(customerId, conversationNo, requestNo, failed);
            }
            sink.turnFailed(failed);
        }
    }

    /** 乘客回复"对"且最新客服消息是确认提问时，接续确认与默认路线出卡。 */
    private void handleConfirmation(AiTurnEventSink sink, long customerId, String conversationNo,
                                    String requestNo, AiTurnBeginResponse begin) {
        AssistantMessageView question = begin.latestAssistantMessage();
        ConfirmationPayload payload = parseConfirmationPayload(question.payloadJson());

        RouteConfirmResponse confirm = unwrap(mapRouteAi.confirm(new RouteConfirmRequest(
                customerId, conversationNo, question.requestNo(), payload.conditionVersion(), CONFIRMATION_REPLY)),
                "confirm");
        switch (confirm.status()) {
            case CONFIRMED -> {
                RouteCardDto card = unwrap(mapRouteAi.defaultRoute(new DefaultRouteRequest(
                        customerId, conversationNo, payload.conditionVersion())), "default");
                String cardText = buildRouteCardText(card);
                // 持久化摘要不含折线；完整折线只在 SSE 内容事件中给出。
                String persistedPayload = toPersistedPayload(card);
                AiTurnCompleteResponse done = unwrap(passengerAi.complete(conversationNo, requestNo,
                        new AiTurnCompleteRequest(customerId, "ROUTE_CARD", cardText, persistedPayload)),
                        "complete");
                sink.content("route.card", new ContentEvent(requestNo, new AssistantMessageEvent(
                        done.messageNo(), requestNo, "ASSISTANT", "ROUTE_CARD", cardText,
                        Map.of("routeCard", objectMapper.convertValue(card, Map.class)), done.createdAt())));
            }
            case NOT_ACCEPTED -> completeText(sink, customerId, conversationNo, requestNo,
                    CONFIRMATION_NOT_ACCEPTED, null);
            default -> completeText(sink, customerId, conversationNo, requestNo,
                    CONFIRMATION_EXPIRED, null);
        }
    }

    /**
     * 非确认文字：提取两端 → 追问 / 澄清 / 生成确认提问。
     *
     * conditionVersion 直接使用本轮用户消息的会话内序号（begin.requestVersion）：
     * 会话内单调递增，端点一旦变更，新版本使旧条件与旧路线快照自然失效，
     * 符合"乘客更改任一端时使旧路线与选项失效"的契约。
     */
    private void handleFreshText(AiTurnEventSink sink, long customerId, String conversationNo,
                                 String requestNo, long conditionVersion, String content) {
        ExtractEndpointsResponse extracted = unwrap(mapRouteAi.extract(
                new ExtractEndpointsRequest(content)), "extract");
        if (extracted.originName() == null || extracted.destinationName() == null) {
            completeText(sink, customerId, conversationNo, requestNo, ASK_FOR_ENDPOINTS, null);
            return;
        }
        // TODO 主干只支持默认路线规划；extract 的 intent 供后续核查/途经点分支使用。
        RoutePreparationResponse prep = unwrap(mapRouteAi.prepare(new RoutePreparationRequest(
                customerId, conversationNo, requestNo, conditionVersion,
                extracted.originName(), extracted.originRegion(),
                extracted.destinationName(), extracted.destinationRegion(),
                extracted.intent(), content)), "prepare");
        if (READY_FOR_CONFIRMATION.equals(prep.status())) {
            String originName = prep.originFeedback().candidates().getFirst().name();
            String destinationName = prep.destinationFeedback().candidates().getFirst().name();
            String question = "我理解的起点是" + originName + "，终点是" + destinationName + "，对吗？";
            String payloadJson = confirmationPayloadJson(originName, destinationName, conditionVersion);
            completeText(sink, customerId, conversationNo, requestNo, question, payloadJson);
        } else {
            completeText(sink, customerId, conversationNo, requestNo,
                    buildClarification(extracted, prep), null);
        }
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

    /** 确认提问的载荷：携带条件版本与两端名称，供乘客回复"对"时恢复原意图。 */
    private String confirmationPayloadJson(String originName, String destinationName, long conditionVersion) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    CONFIRMATION_MARKER, true,
                    "conditionVersion", conditionVersion,
                    "originName", originName,
                    "destinationName", destinationName));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("确认提问载荷序列化失败", e);
        }
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
        return "ROUTE_CARD".equals(messageType) ? "route.card" : "answer.completed";
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

    private TurnFailedEvent classifyByStatus(String requestNo, int status) {
        return switch (status) {
            case 404 -> new TurnFailedEvent(requestNo, "AI_CONVERSATION_NOT_FOUND", "会话不存在或已失效");
            case 409 -> new TurnFailedEvent(requestNo, "AI_REQUEST_IN_PROGRESS", "同会话另有请求进行中，请稍后重试");
            case 504 -> new TurnFailedEvent(requestNo, "AI_MAP_TIMEOUT", "地图服务暂时不可用，请稍后重试");
            default -> new TurnFailedEvent(requestNo, "AI_INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
        };
    }

    private void failQuietly(long customerId, String conversationNo, String requestNo, TurnFailedEvent failed) {
        try {
            passengerAi.fail(conversationNo, requestNo,
                    new AiTurnFailRequest(customerId, failed.code(), failed.message()));
        } catch (Exception ignored) {
            log.warn("AI 客服轮次失败消息落库失败 requestNo={}", requestNo);
        }
    }

    private record ConfirmationPayload(long conditionVersion) {
    }
}
