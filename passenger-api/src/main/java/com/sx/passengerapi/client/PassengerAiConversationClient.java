package com.sx.passengerapi.client;

import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnBeginResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnCompleteResponse;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailRequest;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiTurnFailResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.PassengerCoreFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** passenger-service AI 会话内部接口客户端。 */
@FeignClient(name = "passenger-service", contextId = "passengerAiConversation",
        configuration = PassengerCoreFeignConfiguration.class)
public interface PassengerAiConversationClient {

    @PostMapping("/api/v1/internal/ai/conversations")
    ResponseVo<AiConversationCreateResponse> create(@RequestBody AiConversationCreateRequest request);

    @GetMapping("/api/v1/internal/ai/conversations/{conversationNo}/messages")
    ResponseVo<AiMessageListResponse> listMessages(@PathVariable("conversationNo") String conversationNo,
                                                   @RequestParam("customerId") long customerId,
                                                   @RequestParam(value = "limit", required = false) Integer limit,
                                                   @RequestParam(value = "beforeSequence", required = false) Long beforeSequence);

    @PostMapping("/api/v1/internal/ai/conversations/{conversationNo}/turns/begin")
    ResponseVo<AiTurnBeginResponse> begin(@PathVariable("conversationNo") String conversationNo,
                                          @RequestBody AiTurnBeginRequest request);

    @PostMapping("/api/v1/internal/ai/conversations/{conversationNo}/turns/{requestNo}/complete")
    ResponseVo<AiTurnCompleteResponse> complete(@PathVariable("conversationNo") String conversationNo,
                                                @PathVariable("requestNo") String requestNo,
                                                @RequestBody AiTurnCompleteRequest request);

    @PostMapping("/api/v1/internal/ai/conversations/{conversationNo}/turns/{requestNo}/fail")
    ResponseVo<AiTurnFailResponse> fail(@PathVariable("conversationNo") String conversationNo,
                                        @PathVariable("requestNo") String requestNo,
                                        @RequestBody AiTurnFailRequest request);
}
