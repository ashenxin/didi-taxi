package com.sx.map.ai.model;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 乘客路线意图与地点提取使用的独立模型入口。
 *
 * 该类型刻意不继承 {@code ChatModel}，避免 Qwen 提取模型与 DeepSeek Agent 模型
 * 同时注册后产生注入歧义，也避免提取服务误用全局聊天模型。
 */
@FunctionalInterface
public interface PassengerRouteExtractionModel {

    ChatResponse call(Prompt prompt);
}
