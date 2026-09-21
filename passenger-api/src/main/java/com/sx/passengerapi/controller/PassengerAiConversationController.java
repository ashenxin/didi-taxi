package com.sx.passengerapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationDeleteResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationListResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiRequestStatusResponse;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ai.MessageStreamRequest;
import com.sx.passengerapi.model.ai.PlaceChoiceStreamRequest;
import com.sx.passengerapi.model.ai.RouteCheckModeChoiceStreamRequest;
import com.sx.passengerapi.service.AiRouteTurnService;
import com.sx.passengerapi.service.SseAiTurnEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 乘客端 AI 客服公开接口。
 * 身份由网关注入的 X-User-Id 提供（PassengerJwtAuthFilter 已复验）；
 * 流式接口预校验失败走标准 JSON 错误，不发 SSE。
 */
@RestController
@RequestMapping("/app/api/v1/ai/conversations")
public class PassengerAiConversationController {

    private static final Logger log = LoggerFactory.getLogger(PassengerAiConversationController.class);
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final long SSE_TIMEOUT_MILLIS = 90_000L;
    private static final long SSE_HEARTBEAT_SECONDS = 15L;

    private final AiRouteTurnService turnService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor aiTurnExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public PassengerAiConversationController(AiRouteTurnService turnService,
                                             ObjectMapper objectMapper,
                                             @Qualifier("aiTurnExecutor") ThreadPoolTaskExecutor aiTurnExecutor,
                                             @Qualifier("aiSseHeartbeatScheduler")
                                             ScheduledExecutorService heartbeatScheduler) {
        this.turnService = turnService;
        this.objectMapper = objectMapper;
        this.aiTurnExecutor = aiTurnExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostMapping
    public ResponseVo<AiConversationCreateResponse> create(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        requireLogin(passengerId);
        return ResultUtil.success(turnService.createConversation(passengerId, normalizeIdempotencyKey(idempotencyKey)));
    }

    /** 历史消息分页：不带 beforeSequence 返回最新一页，携带则向前翻更早消息。 */
    @GetMapping("/{conversationNo}/messages")
    public ResponseVo<AiMessageListResponse> listMessages(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @PathVariable String conversationNo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long beforeSequence) {
        requireLogin(passengerId);
        if (limit != null && (limit < 1 || limit > 50)) {
            throw new BizErrorException(400, "分页大小须为1至50");
        }
        return ResultUtil.success(turnService.listMessages(passengerId, conversationNo, limit, beforeSequence));
    }

    @GetMapping
    public ResponseVo<AiConversationListResponse> listConversations(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String beforeConversationNo) {
        requireLogin(passengerId);
        if (limit != null && (limit < 1 || limit > 50)) {
            throw new BizErrorException(400, "分页大小须为1至50");
        }
        return ResultUtil.success(turnService.listConversations(
                passengerId, limit, beforeConversationNo));
    }

    @GetMapping("/{conversationNo}/requests/{requestNo}")
    public ResponseVo<AiRequestStatusResponse> requestStatus(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @PathVariable String conversationNo,
            @PathVariable String requestNo) {
        requireLogin(passengerId);
        return ResultUtil.success(turnService.requestStatus(
                passengerId, conversationNo, requestNo));
    }

    @DeleteMapping("/{conversationNo}")
    public ResponseVo<AiConversationDeleteResponse> deleteConversation(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @PathVariable String conversationNo) {
        requireLogin(passengerId);
        return ResultUtil.success(turnService.deleteConversation(passengerId, conversationNo));
    }

    @PostMapping("/{conversationNo}/messages/stream")
    public SseEmitter stream(@RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
                             @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                             @PathVariable String conversationNo,
                             @RequestBody MessageStreamRequest body) {
        requireLogin(passengerId);
        String key = normalizeIdempotencyKey(idempotencyKey);
        String content = body == null || body.content() == null ? "" : body.content().strip();
        if (content.isEmpty() || content.length() > 1000) {
            throw new BizErrorException(400, "消息内容长度须为1至1000");
        }

        return submitTurn(conversationNo, sink ->
                turnService.execute(sink, passengerId, conversationNo, content, key));
    }

    @PostMapping("/{conversationNo}/route-tasks/{taskNo}/place-choice/stream")
    public SseEmitter selectPlace(@RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
                                  @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                                  @PathVariable String conversationNo, @PathVariable String taskNo,
                                  @RequestBody PlaceChoiceStreamRequest body) {
        requireLogin(passengerId);
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (body == null || body.expectedRequestVersion() <= 0
                || body.placeChoiceId() == null || !body.placeChoiceId().matches("PC-[0-9a-fA-F-]{36}")
                || body.choiceName() == null || body.choiceName().isBlank()
                || body.choiceName().strip().length() > 80
                || (body.choiceCity() != null && body.choiceCity().strip().length() > 30)) {
            throw new BizErrorException(400, "地点选项参数无效");
        }
        if (taskNo == null || !taskNo.matches("AIT-[0-9a-fA-F-]{36}")) {
            throw new BizErrorException(400, "地点选择任务编号无效");
        }
        return submitTurn(conversationNo, sink -> turnService.selectPlaceChoice(sink,
                passengerId, conversationNo, taskNo, body.placeChoiceId(),
                body.expectedRequestVersion(), body.choiceName(), body.choiceCity(), key));
    }

    @PostMapping("/{conversationNo}/route-check-tasks/{taskNo}/mode-choice/stream")
    public SseEmitter selectRouteCheckMode(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String conversationNo,
            @PathVariable String taskNo,
            @RequestBody RouteCheckModeChoiceStreamRequest body) {
        requireLogin(passengerId);
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (body == null || body.expectedRequestVersion() <= 0
                || body.checkMode() == null
                || !java.util.Set.of("PASS_NEARBY", "USE_EXIT", "ENTER_HIGHWAY", "VISIT_PLACE")
                .contains(body.checkMode())
                || body.choiceLabel() == null || body.choiceLabel().isBlank()
                || body.choiceLabel().strip().length() > 80) {
            throw new BizErrorException(400, "核查方式选项参数无效");
        }
        if (taskNo == null || !taskNo.matches("RCT-[0-9a-fA-F-]{36}")) {
            throw new BizErrorException(400, "核查方式任务编号无效");
        }
        return submitTurn(conversationNo, sink -> turnService.selectCurrentRouteCheckMode(sink,
                passengerId, conversationNo, taskNo, body.checkMode(),
                body.expectedRequestVersion(), body.choiceLabel(), key));
    }

    private SseEmitter submitTurn(String conversationNo, Consumer<SseAiTurnEventSink> work) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseAiTurnEventSink sink = new SseAiTurnEventSink(emitter, objectMapper);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Future<?>> worker = new AtomicReference<>();
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                sink::ping, SSE_HEARTBEAT_SECONDS, SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        Runnable closeHeartbeat = () -> {
            if (closed.compareAndSet(false, true)) {
                heartbeat.cancel(false);
            }
        };
        emitter.onCompletion(closeHeartbeat);
        emitter.onError(error -> {
            closeHeartbeat.run();
            log.warn("AI 客服 SSE 异常 conversationNo={} type={}",
                    conversationNo, error.getClass().getSimpleName());
        });
        emitter.onTimeout(() -> {
            closeHeartbeat.run();
            Future<?> running = worker.get();
            if (running != null) {
                running.cancel(true);
            }
            sink.turnFailed(new com.sx.passengerapi.model.ai.AiSseEvents.TurnFailedEvent(
                    null, "AI_STREAM_TIMEOUT", "本轮处理超时，请重新发送消息"));
            log.warn("AI 客服 SSE 超时 conversationNo={}", conversationNo);
        });
        try {
            worker.set(aiTurnExecutor.submit(() -> {
                try {
                    work.accept(sink);
                } finally {
                    emitter.complete();
                }
            }));
        } catch (TaskRejectedException e) {
            sink.turnFailed(new com.sx.passengerapi.model.ai.AiSseEvents.TurnFailedEvent(
                    null, "AI_SERVER_BUSY", "客服当前请求较多，请稍后重试"));
            emitter.complete();
        }
        return emitter;
    }

    private static void requireLogin(Long passengerId) {
        if (passengerId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizErrorException(400, "Idempotency-Key不能为空");
        }
        String normalized = idempotencyKey.strip();
        if (normalized.length() > 128) {
            throw new BizErrorException(400, "Idempotency-Key长度不能超过128");
        }
        return normalized;
    }
}
