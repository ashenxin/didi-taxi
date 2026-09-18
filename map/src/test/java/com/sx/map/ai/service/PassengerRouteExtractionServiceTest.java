package com.sx.map.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.dto.ExtractEndpointsResponse;
import com.sx.map.exception.AiRouteBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PassengerRouteExtractionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatModel chatModel = mock(ChatModel.class);
    private PassengerRouteExtractionService service;

    @BeforeEach
    void setUp() {
        service = new PassengerRouteExtractionService(chatModelProvider(chatModel), objectMapper,
                new ClassPathResource("ai/passenger-route-extraction-prompt.md"), 30);
    }

    @Test
    void parsesCleanJsonOutput() {
        modelReturns("{\"originName\":\"德清高速路口\",\"destinationName\":\"西溪湿地\","
                + "\"originRegion\":null,\"destinationRegion\":null,\"intent\":\"DEFAULT_ROUTE\"}");

        ExtractEndpointsResponse result = service.extract("帮我规划一条从德清高速路口到西溪湿地的路线");

        assertThat(result.originName()).isEqualTo("德清高速路口");
        assertThat(result.destinationName()).isEqualTo("西溪湿地");
        assertThat(result.intent()).isEqualTo("DEFAULT_ROUTE");
    }

    @Test
    void stripsCodeFenceFromOutput() {
        modelReturns("```json\n{\"originName\":\"A\",\"destinationName\":\"B\"}\n```");

        ExtractEndpointsResponse result = service.extract("从A到B");

        assertThat(result.originName()).isEqualTo("A");
        assertThat(result.destinationName()).isEqualTo("B");
    }

    @Test
    void extractsJsonBetweenExplanationText() {
        modelReturns("好的，提取结果如下：{\"originName\":\"A\",\"destinationName\":\"B\"}以上。");

        ExtractEndpointsResponse result = service.extract("从A到B");

        assertThat(result.originName()).isEqualTo("A");
        assertThat(result.destinationName()).isEqualTo("B");
    }

    @Test
    void invalidJsonReturnsAllNull() {
        modelReturns("这不是JSON");

        ExtractEndpointsResponse result = service.extract("随便说点什么");

        assertThat(result.originName()).isNull();
        assertThat(result.destinationName()).isNull();
        assertThat(result.intent()).isEqualTo("DEFAULT_ROUTE");
    }

    @Test
    void reasoningTextWithBracesBeforeFinalJsonStillParses() {
        modelReturns("让我思考一下：按照{格式要求}输出。"
                + "{\"originName\":\"德清高速路口\",\"destinationName\":\"西溪湿地\"}");

        ExtractEndpointsResponse result = service.extract("从德清高速路口到西溪湿地");

        assertThat(result.originName()).isEqualTo("德清高速路口");
        assertThat(result.destinationName()).isEqualTo("西溪湿地");
    }

    @Test
    void missingDestinationKeepsItNull() {
        modelReturns("{\"originName\":\"德清高速路口\",\"destinationName\":null}");

        ExtractEndpointsResponse result = service.extract("我要从德清高速路口出发");

        assertThat(result.originName()).isEqualTo("德清高速路口");
        assertThat(result.destinationName()).isNull();
    }

    @Test
    void chatModelFailureReturnsProviderUnavailable() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("upstream down"));

        assertThatThrownBy(() -> service.extract("从A到B"))
                .isInstanceOf(AiRouteBusinessException.class)
                .satisfies(e -> {
                    AiRouteBusinessException failure = (AiRouteBusinessException) e;
                    assertThat(failure.getStableCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
                    assertThat(failure.getMessage()).isEqualTo("模型服务暂时不可用，请稍后重试");
                    assertThat(failure.getCause()).isInstanceOf(java.util.concurrent.ExecutionException.class);
                });
    }

    @Test
    void missingChatModelThrowsProviderUnavailable() {
        PassengerRouteExtractionService withoutModel = new PassengerRouteExtractionService(
                chatModelProvider(null), objectMapper,
                new ClassPathResource("ai/passenger-route-extraction-prompt.md"), 30);

        assertThatThrownBy(() -> withoutModel.extract("从A到B"))
                .isInstanceOf(AiRouteBusinessException.class)
                .satisfies(e -> assertThat(((AiRouteBusinessException) e).getStableCode())
                        .isEqualTo("AI_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void slowModelTimesOut() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("{\"originName\":\"A\",\"destinationName\":\"B\"}"))));
        });
        PassengerRouteExtractionService shortTimeout = new PassengerRouteExtractionService(
                chatModelProvider(chatModel), objectMapper,
                new ClassPathResource("ai/passenger-route-extraction-prompt.md"), 0);

        assertThatThrownBy(() -> shortTimeout.extract("从A到B"))
                .isInstanceOf(AiRouteBusinessException.class)
                .satisfies(e -> assertThat(((AiRouteBusinessException) e).getStableCode())
                        .isEqualTo("AI_PROVIDER_TIMEOUT"));
    }

    private void modelReturns(String text) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private static ObjectProvider<ChatModel> chatModelProvider(ChatModel chatModel) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (chatModel != null) {
            beanFactory.addBean("chatModel", chatModel);
        }
        return beanFactory.getBeanProvider(ChatModel.class);
    }
}
