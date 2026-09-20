package com.sx.map.ai.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.sx.map.ai.model.PassengerRouteExtractionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * 路线提取专用的 Qwen 模型。
 *
 * 全局 {@code spring.ai.model.chat} 继续选择 DeepSeek，供复杂 Agent 使用；这里单独创建
 * DashScope 客户端，只负责短文本分类和字段提取。关闭思考并要求 JSON，可减少延迟和无关输出。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.dashscope.api-key")
public class PassengerRouteExtractionModelConfiguration {

    @Bean
    public PassengerRouteExtractionModel passengerRouteExtractionModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${map.ai.extraction.model:qwen3.8-flash}") String model,
            @Value("${map.ai.extraction.max-tokens:256}") int maxTokens,
            @Value("${map.ai.extraction.connect-timeout-seconds:3}") int connectTimeoutSeconds,
            @Value("${map.ai.extraction.read-timeout-seconds:12}") int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutSeconds * 1_000);
        requestFactory.setReadTimeout(readTimeoutSeconds * 1_000);

        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(model)
                .maxToken(maxTokens)
                .temperature(0.0)
                .enableThinking(false)
                .multiModel(true)
                .responseFormat(DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_OBJECT)
                        .build())
                .build();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                // 提取请求由上层直接反馈失败；这里不做隐式重试，避免叠加等待时间。
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
                .build();
        return chatModel::call;
    }
}
