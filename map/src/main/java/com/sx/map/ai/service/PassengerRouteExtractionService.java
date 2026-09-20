package com.sx.map.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.dto.ExtractEndpointsResponse;
import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.ai.model.PassengerRouteExtractionModel;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.exception.AiRouteBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
    private static final String CURRENT_ROUTE_CHECK = "CURRENT_ROUTE_CHECK";

    private final ObjectProvider<PassengerRouteExtractionModel> extractionModelProvider;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final Duration modelTimeout;
    private final Executor extractionExecutor;

    public PassengerRouteExtractionService(ObjectProvider<PassengerRouteExtractionModel> extractionModelProvider,
                                           ObjectMapper objectMapper,
                                           @Value("classpath:ai/passenger-route-extraction-prompt.md") Resource systemPromptResource,
                                           @Value("${map.ai.extract-timeout-seconds:15}") long extractTimeoutSeconds,
                                           @Qualifier("passengerRouteExtractionExecutor") Executor extractionExecutor) {
        this.extractionModelProvider = extractionModelProvider;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadSystemPrompt(systemPromptResource);
        this.modelTimeout = Duration.ofSeconds(extractTimeoutSeconds);
        this.extractionExecutor = extractionExecutor;
    }

    /** 提取两端；地点缺失或输出无法解析时对应字段为 null，模型调用故障抛出稳定错误。 */
    public ExtractEndpointsResponse extract(String userText) {
        PassengerRouteExtractionModel extractionModel = extractionModelProvider.getIfAvailable();
        if (extractionModel == null) {
            throw new AiRouteBusinessException("AI_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置路线提取模型，请检查 spring.ai.dashscope.api-key");
        }
        CompletableFuture<org.springframework.ai.chat.model.ChatResponse> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> extractionModel.call(buildPrompt(userText)), extractionExecutor);
        } catch (RejectedExecutionException e) {
            throw new AiRouteBusinessException("AI_PROVIDER_BUSY", HttpStatus.SERVICE_UNAVAILABLE,
                    "模型服务繁忙，请稍后重试", e);
        }
        org.springframework.ai.chat.model.ChatResponse response;
        try {
            response = future.get(modelTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("起终点提取模型超时");
            throw new AiRouteBusinessException("AI_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                    "模型提取超时，请稍后重试");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("起终点提取被中断");
            throw new AiRouteBusinessException("AI_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                    "模型提取被中断，请稍后重试");
        } catch (Exception e) {
            log.warn("起终点提取模型调用失败 type={}", e.getClass().getSimpleName());
            throw new AiRouteBusinessException("AI_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "模型服务暂时不可用，请稍后重试", e);
        }
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new AiRouteBusinessException("AI_PROVIDER_INVALID_RESPONSE", HttpStatus.BAD_GATEWAY,
                    "模型返回内容无效，请稍后重试");
        }
        String raw = response.getResult().getOutput().getText();
        return enforceExactCheckPlaceMention(parseModelOutput(raw), userText);
    }

    /**
     * 核查地点必须来自乘客原话中的连续片段。模型可以识别意图和截取文字，但不能把“五常”
     * 改写成“五常收费站”。这里不理解任何自然语言别名，只校验提取结果是否可追溯到原文。
     */
    private static ExtractEndpointsResponse enforceExactCheckPlaceMention(
            ExtractEndpointsResponse extracted, String userText) {
        if (extracted == null || !CURRENT_ROUTE_CHECK.equals(extracted.intent())
                || extracted.checkPlaceName() == null
                || (userText != null && userText.contains(extracted.checkPlaceName()))) {
            return extracted;
        }
        return new ExtractEndpointsResponse(
                extracted.originName(), extracted.destinationName(),
                extracted.originRegion(), extracted.destinationRegion(),
                extracted.intent(), null, extracted.checkPlaceRegion(), extracted.checkMode());
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
            String intent = normalizeIntent(textOrNull(node, "intent", DEFAULT_INTENT));
            boolean currentRouteCheck = CURRENT_ROUTE_CHECK.equals(intent);
            return new ExtractEndpointsResponse(
                    textOrNull(node, "originName"),
                    textOrNull(node, "destinationName"),
                    textOrNull(node, "originRegion"),
                    textOrNull(node, "destinationRegion"),
                    intent,
                    currentRouteCheck ? textOrNull(node, "checkPlaceName") : null,
                    currentRouteCheck ? textOrNull(node, "checkPlaceRegion") : null,
                    currentRouteCheck ? normalizeCheckMode(textOrNull(node, "checkMode")) : null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 未知意图不能直接驱动业务分支，统一回落到默认路线。 */
    private static String normalizeIntent(String intent) {
        try {
            return PassengerRouteIntent.valueOf(intent).name();
        } catch (IllegalArgumentException e) {
            return DEFAULT_INTENT;
        }
    }

    /** 未识别或模型自造的核查含义统一进入追问分支。 */
    private static String normalizeCheckMode(String mode) {
        if (mode == null) {
            return CurrentRouteCheckMode.UNSPECIFIED.name();
        }
        try {
            return CurrentRouteCheckMode.valueOf(mode).name();
        } catch (IllegalArgumentException e) {
            return CurrentRouteCheckMode.UNSPECIFIED.name();
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
