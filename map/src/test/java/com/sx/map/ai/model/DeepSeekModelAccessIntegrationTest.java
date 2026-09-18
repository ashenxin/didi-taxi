package com.sx.map.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DeepSeek 官方 API 接入的手动集成测试。
 *
 * 该测试会访问外部大模型并产生 Token 消耗，因此默认跳过。
 * 只有显式设置 RUN_DEEPSEEK_INTEGRATION_TESTS=true，并配置对应 API Key 后才会执行。
 *
 * 执行方式：
 * RUN_DEEPSEEK_INTEGRATION_TESTS=true DEEPSEEK_API_KEY=你的Key \
 * mvn -pl map -Dtest=DeepSeekModelAccessIntegrationTest test
 */
class DeepSeekModelAccessIntegrationTest {

    private static final String ENABLE_SWITCH = "RUN_DEEPSEEK_INTEGRATION_TESTS";
    private static final String DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY";
    private static final String MODEL_NAME = "deepseek-v4-pro";
    private static final String TEST_PROMPT = "只回复两个字：杭州";

    @Test
    void callsDeepSeekThroughOfficialApi() {
        String apiKey = requiredApiKey(DEEPSEEK_API_KEY);

        // 使用 Spring AI 的 DeepSeek 专用 starter，直接请求 DeepSeek 官方 API。
        // DeepSeekApi 默认地址是 https://api.deepseek.com，无需再手写 HTTP 客户端。
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .build();
        ChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(MODEL_NAME)
                        .temperature(0.1)
                        .maxTokens(64)
                        .build())
                .build();

        String answer = chatModel.call(TEST_PROMPT);

        assertThat(answer).isNotBlank();
    }

    /**
     * 双重开关可以避免开发机已经配置 API Key 时，普通单元测试意外产生外部调用费用。
     */
    private static String requiredApiKey(String keyName) {
        assumeTrue("true".equalsIgnoreCase(System.getenv(ENABLE_SWITCH)),
                "未显式开启 DeepSeek 手动集成测试");
        String apiKey = System.getenv(keyName);
        assumeTrue(apiKey != null && !apiKey.isBlank(), "未配置环境变量 " + keyName);
        return apiKey;
    }
}
