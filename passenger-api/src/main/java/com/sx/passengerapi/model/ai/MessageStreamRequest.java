package com.sx.passengerapi.model.ai;

/** 文字轮次流式接口请求体，只接收聊天文字。 */
public record MessageStreamRequest(String content) {
}
