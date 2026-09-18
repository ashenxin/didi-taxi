package com.sx.passengerapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiConversationCreateResponse;
import com.sx.passengerapi.common.exception.GlobalExceptionHandler;
import com.sx.passengerapi.service.AiRouteTurnService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PassengerAiConversationControllerTest {

    private final AiRouteTurnService turnService = mock(AiRouteTurnService.class);
    private ThreadPoolTaskExecutor executor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("ai-turn-test-");
        executor.initialize();
        mvc = MockMvcBuilders.standaloneSetup(new PassengerAiConversationController(
                turnService, new ObjectMapper(), executor))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void createRequiresLoginAndIdempotencyKey() throws Exception {
        mvc.perform(post("/app/api/v1/ai/conversations")
                        .header("Idempotency-Key", "create-1"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/app/api/v1/ai/conversations")
                        .header("X-User-Id", "10001"))
                .andExpect(status().isBadRequest());

        when(turnService.createConversation(10001L, "create-1"))
                .thenReturn(new AiConversationCreateResponse("AIC-1"));
        mvc.perform(post("/app/api/v1/ai/conversations")
                        .header("X-User-Id", "10001")
                        .header("Idempotency-Key", "create-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationNo").value("AIC-1"));
    }

    @Test
    void listMessagesRequiresLoginAndValidatesPageSize() throws Exception {
        mvc.perform(get("/app/api/v1/ai/conversations/AIC-1/messages"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/app/api/v1/ai/conversations/AIC-1/messages")
                        .header("X-User-Id", "10001")
                        .param("limit", "51"))
                .andExpect(status().isBadRequest());

        when(turnService.listMessages(10001L, "AIC-1", 20, null))
                .thenReturn(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse(
                        java.util.List.of(), false));
        mvc.perform(get("/app/api/v1/ai/conversations/AIC-1/messages")
                        .header("X-User-Id", "10001")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isArray())
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void listMessagesReturnsClientMessageNoForOwnUserMessage() throws Exception {
        var userMessage = new com.sx.passengerapi.client.dto.ai.AiConversationDtos.MessageView(
                "AIM-1", "REQ-1", "turn-1", "USER", "TEXT", "从A到B", null, 1,
                "COMPLETED", null);
        var assistantMessage = new com.sx.passengerapi.client.dto.ai.AiConversationDtos.MessageView(
                "AIM-2", "REQ-1", null, "ASSISTANT", "TEXT", "好的", null, 2,
                "COMPLETED", null);
        when(turnService.listMessages(10001L, "AIC-1", 20, null))
                .thenReturn(new com.sx.passengerapi.client.dto.ai.AiConversationDtos.AiMessageListResponse(
                        java.util.List.of(userMessage, assistantMessage), false));

        mvc.perform(get("/app/api/v1/ai/conversations/AIC-1/messages")
                        .header("X-User-Id", "10001")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].clientMessageNo").value("turn-1"))
                .andExpect(jsonPath("$.data.messages[1].clientMessageNo").isEmpty());
    }

    @Test
    void streamPreValidationFailsWithJsonInsteadOfSse() throws Exception {
        mvc.perform(post("/app/api/v1/ai/conversations/AIC-1/messages/stream")
                        .header("X-User-Id", "10001")
                        .header("Idempotency-Key", "turn-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/app/api/v1/ai/conversations/AIC-1/messages/stream")
                        .header("X-User-Id", "10001")
                        .header("Idempotency-Key", "turn-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(turnService, never()).execute(org.mockito.ArgumentMatchers.any(),
                anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void streamStartsAsyncAndCompletesEmitter() throws Exception {
        MvcResult result = mvc.perform(post("/app/api/v1/ai/conversations/AIC-1/messages/stream")
                        .header("X-User-Id", "10001")
                        .header("Idempotency-Key", "turn-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"帮我规划一条路线\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());

        verify(turnService).execute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(10001L),
                org.mockito.ArgumentMatchers.eq("AIC-1"),
                org.mockito.ArgumentMatchers.eq("帮我规划一条路线"),
                org.mockito.ArgumentMatchers.eq("turn-1"));
    }
}
