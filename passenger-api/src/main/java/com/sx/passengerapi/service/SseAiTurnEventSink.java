package com.sx.passengerapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.model.ai.AiSseEvents.ContentEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnCompletedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnFailedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/** SseEmitter 适配器：每个事件一行 JSON；客户端断开后的发送失败只记日志。 */
public class SseAiTurnEventSink implements AiTurnEventSink {

    private static final Logger log = LoggerFactory.getLogger(SseAiTurnEventSink.class);

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseAiTurnEventSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void turnStarted(TurnStartedEvent event) {
        send("turn.started", event);
    }

    @Override
    public void content(String eventName, ContentEvent event) {
        send(eventName, event);
    }

    @Override
    public void turnCompleted(TurnCompletedEvent event) {
        send("turn.completed", event);
    }

    @Override
    public void turnFailed(TurnFailedEvent event) {
        send("turn.failed", event);
    }

    private void send(String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开或结果已持久化：本轮业务状态不受影响，静默收尾。
            log.debug("AI 客服 SSE 发送失败 event={} type={}", eventName, e.getClass().getSimpleName());
        }
    }
}
