package com.sx.passengerapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ai.MessageStreamRequest;
import com.sx.passengerapi.service.AiRouteTurnService;
import com.sx.passengerapi.service.SseAiTurnEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private static final long SSE_TIMEOUT_MILLIS = 60_000L;

    private final AiRouteTurnService turnService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor aiTurnExecutor;

    public PassengerAiConversationController(AiRouteTurnService turnService,
                                             ObjectMapper objectMapper,
                                             @Qualifier("aiTurnExecutor") ThreadPoolTaskExecutor aiTurnExecutor) {
        this.turnService = turnService;
        this.objectMapper = objectMapper;
        this.aiTurnExecutor = aiTurnExecutor;
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

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitter.onTimeout(() -> log.warn("AI 客服 SSE 超时 conversationNo={}", conversationNo));
        emitter.onError(error -> log.warn("AI 客服 SSE 异常 conversationNo={}", conversationNo));
        aiTurnExecutor.execute(() -> {
            try {
                turnService.execute(new SseAiTurnEventSink(emitter, objectMapper),
                        passengerId, conversationNo, content, key);
            } finally {
                // 结果已持久化或已按 turn.failed 收尾；断线恢复依赖后续历史接口。
                emitter.complete();
            }
        });
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
