package com.sx.passenger.ai.controller;

import com.sx.passenger.ai.dto.AiConversationCreateRequest;
import com.sx.passenger.ai.dto.AiConversationCreateResponse;
import com.sx.passenger.ai.dto.AiMessageListResponse;
import com.sx.passenger.ai.dto.AiTurnBeginRequest;
import com.sx.passenger.ai.dto.AiTurnBeginResponse;
import com.sx.passenger.ai.dto.AiTurnCompleteRequest;
import com.sx.passenger.ai.dto.AiTurnCompleteResponse;
import com.sx.passenger.ai.dto.AiTurnFailRequest;
import com.sx.passenger.ai.dto.AiTurnFailResponse;
import com.sx.passenger.ai.service.AiConversationService;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 客服会话内部接口，供 passenger-api 编排调用。
 * 鉴权由 PassengerInternalAuthFilter 统一处理（X-Internal-Service-Token）。
 */
@RestController
@RequestMapping("/api/v1/internal/ai/conversations")
public class AiConversationInternalController {

    private final AiConversationService conversationService;

    public AiConversationInternalController(AiConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ResponseVo<AiConversationCreateResponse> create(@RequestBody AiConversationCreateRequest request) {
        return ResultUtil.success(conversationService.create(request.customerId(), request.idempotencyKey()));
    }

    @GetMapping("/{conversationNo}/messages")
    public ResponseVo<AiMessageListResponse> listMessages(@PathVariable String conversationNo,
                                                          @RequestParam long customerId,
                                                          @RequestParam(required = false) Integer limit,
                                                          @RequestParam(required = false) Long beforeSequence) {
        return ResultUtil.success(conversationService.listMessages(
                conversationNo, customerId, limit, beforeSequence));
    }

    @PostMapping("/{conversationNo}/turns/begin")
    public ResponseVo<AiTurnBeginResponse> begin(@PathVariable String conversationNo,
                                                 @RequestBody AiTurnBeginRequest request) {
        return ResultUtil.success(conversationService.begin(
                conversationNo, request.customerId(), request.idempotencyKey(), request.content()));
    }

    @PostMapping("/{conversationNo}/turns/{requestNo}/complete")
    public ResponseVo<AiTurnCompleteResponse> complete(@PathVariable String conversationNo,
                                                       @PathVariable String requestNo,
                                                       @RequestBody AiTurnCompleteRequest request) {
        return ResultUtil.success(conversationService.complete(
                conversationNo, requestNo, request.customerId(),
                request.messageType(), request.content(), request.payloadJson()));
    }

    @PostMapping("/{conversationNo}/turns/{requestNo}/fail")
    public ResponseVo<AiTurnFailResponse> fail(@PathVariable String conversationNo,
                                               @PathVariable String requestNo,
                                               @RequestBody AiTurnFailRequest request) {
        return ResultUtil.success(conversationService.fail(
                conversationNo, requestNo, request.customerId(),
                request.failureCode(), request.failureMessage()));
    }
}
