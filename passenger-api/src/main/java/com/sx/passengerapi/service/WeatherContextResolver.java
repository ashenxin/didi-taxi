package com.sx.passengerapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.dto.ai.AiConversationDtos.AssistantMessageView;

/**
 * 从本会话上一轮已持久化的天气消息中恢复上下文。
 *
 * 两类上下文：
 * 1. 上一轮是天气回答：可恢复城市，用于“后天呢/那里呢”；
 * 2. 上一轮是天气追问：保存了原始日期偏移，用于乘客只回“德清”时继续天气。
 *
 * 只读取已落库的天气 payload，不读取完整聊天正文，不跨会话，不使用 GPS、地图页或
 * 当前路线。显式城市和显式日期始终优先。
 */
final class WeatherContextResolver {

    private WeatherContextResolver() {
    }

    record Context(boolean weatherAnswer, String city,
                   boolean pendingAsk, Integer pendingDayOffset) {

        boolean anyWeatherContext() {
            return weatherAnswer || pendingAsk;
        }

        static Context none() {
            return new Context(false, null, false, null);
        }
    }

    static Context from(AssistantMessageView latest, ObjectMapper objectMapper) {
        if (latest == null
                || !"ASSISTANT".equals(latest.role())
                || !"TEXT".equals(latest.messageType())
                || latest.status() != null && !"COMPLETED".equals(latest.status())
                || latest.payloadJson() == null || latest.payloadJson().isBlank()) {
            return Context.none();
        }
        try {
            JsonNode payload = objectMapper.readTree(latest.payloadJson());
            boolean weatherAnswer = false;
            String city = null;
            JsonNode weather = payload.path("weather");
            if (weather.isObject()) {
                weatherAnswer = true;
                JsonNode location = weather.path("location");
                city = firstText(
                        location.path("city").asText(null),
                        location.path("name").asText(null),
                        location.path("district").asText(null));
            }
            boolean pendingAsk = false;
            Integer pendingDayOffset = null;
            JsonNode ask = payload.path("weatherContext");
            if (ask.isObject()) {
                pendingAsk = true;
                if (ask.path("dayOffset").isInt()) {
                    pendingDayOffset = ask.path("dayOffset").asInt();
                }
            }
            if (!weatherAnswer && !pendingAsk) {
                return Context.none();
            }
            return new Context(weatherAnswer, city, pendingAsk, pendingDayOffset);
        } catch (Exception e) {
            return Context.none();
        }
    }

    /** 展示和回查时去掉城市末尾的“市”，例如“杭州市” -> “杭州”；区县保持不变。 */
    static String displayCity(String city) {
        String value = city == null ? null : city.strip();
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() > 1 && value.endsWith("市")
                ? value.substring(0, value.length() - 1) : value;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
