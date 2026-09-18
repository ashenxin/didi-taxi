package com.sx.map.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.dto.ExtractEndpointsResponse;
import com.sx.map.exception.AiRouteBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 从乘客文字提取起终点的模型服务。
 *
 * 模型输出不是业务权威：这里只提取地点名称，坐标与地点身份由
 * {@code PassengerRouteEndpointResolver} 经高德查询确认。输出无法解析时返回全 null，
 * 由调用方走追问分支；模型调用故障则明确失败，不能误称乘客未提供地点。
 */
@Service
public class PassengerRouteExtractionService {
    private static final Logger log = LoggerFactory.getLogger(PassengerRouteExtractionService.class);
    private static final String DEFAULT_INTENT = "DEFAULT_ROUTE";

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final Duration modelTimeout;

    public PassengerRouteExtractionService(ObjectProvider<ChatModel> chatModelProvider,
                                           ObjectMapper objectMapper,
                                           @Value("classpath:ai/passenger-route-extraction-prompt.md") Resource systemPromptResource,
                                           @Value("${map.ai.extract-timeout-seconds:30}") long extractTimeoutSeconds) {
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadSystemPrompt(systemPromptResource);
        this.modelTimeout = Duration.ofSeconds(extractTimeoutSeconds);
    }

    /** 提取两端；地点缺失或输出无法解析时对应字段为 null，模型调用故障抛出稳定错误。 */
    public ExtractEndpointsResponse extract(String userText) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new AiRouteBusinessException("AI_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置聊天模型，请检查 spring.ai.model.chat 与 api-key");
        }
        String raw;
        try {
            raw = CompletableFuture.supplyAsync(() -> chatModel.call(buildPrompt(userText)))
                    .get(modelTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getResult().getOutput().getText();
        } catch (TimeoutException e) {
            log.warn("起终点提取模型超时");
            throw new AiRouteBusinessException("AI_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                    "模型提取超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("起终点提取被中断");
            throw new AiRouteBusinessException("AI_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                    "模型提取被中断，请稍后重试");
        } catch (Exception e) {
            log.warn("起终点提取模型调用失败 type={}", e.getClass().getSimpleName());
            throw new AiRouteBusinessException("AI_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "模型服务暂时不可用，请稍后重试", e);
        }
        return parseModelOutput(raw);
    }

    private Prompt buildPrompt(String userText) {
        return new Prompt(java.util.List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userText)));
    }

    /**
     * 健壮解析模型输出：容忍 ```json 围栏与前后解释文字，
     * 先取首个 '{' 到末个 '}' 之间的内容；失败时再尝试从最后一个 '{' 截取，
     * 兼容推理模型在思考段里夹带花括号、真正 JSON 放在末尾的情况。任何失败都返回全 null。
     */
    ExtractEndpointsResponse parseModelOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExtractionFailed.allNull();
        }
        String text = raw.strip();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            logParseFailure(text);
            return ExtractionFailed.allNull();
        }
        ExtractEndpointsResponse parsed = tryParse(text.substring(firstBrace, lastBrace + 1));
        if (parsed != null) {
            return parsed;
        }
        int lastOpenBrace = text.lastIndexOf('{');
        if (lastOpenBrace > firstBrace) {
            parsed = tryParse(text.substring(lastOpenBrace, lastBrace + 1));
            if (parsed != null) {
                return parsed;
            }
        }
        logParseFailure(text);
        return ExtractionFailed.allNull();
    }

    private ExtractEndpointsResponse tryParse(String candidate) {
        try {
            JsonNode node = objectMapper.readTree(candidate);
            if (node == null || !node.isObject()) {
                return null;
            }
            return new ExtractEndpointsResponse(
                    textOrNull(node, "originName"),
                    textOrNull(node, "destinationName"),
                    textOrNull(node, "originRegion"),
                    textOrNull(node, "destinationRegion"),
                    textOrNull(node, "intent", DEFAULT_INTENT));
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析失败时记录截断的原始输出，便于排查模型返回形态；不记录密钥与用户身份。 */
    private void logParseFailure(String text) {
        String snippet = text.length() > 500 ? text.substring(0, 500) : text;
        log.warn("起终点提取结果解析失败，模型原始输出前500字符: {}", snippet);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().strip();
    }

    private static String textOrNull(JsonNode node, String field, String defaultValue) {
        String value = textOrNull(node, field);
        return value == null ? defaultValue : value;
    }

    private static String loadSystemPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取起终点提取提示词失败", e);
        }
    }

    private static final class ExtractionFailed {
        private static ExtractEndpointsResponse allNull() {
            return new ExtractEndpointsResponse(null, null, null, null, DEFAULT_INTENT);
        }
    }
}
