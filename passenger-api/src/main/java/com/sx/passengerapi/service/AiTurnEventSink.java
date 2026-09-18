package com.sx.passengerapi.service;

import com.sx.passengerapi.model.ai.AiSseEvents.ContentEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnCompletedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnFailedEvent;
import com.sx.passengerapi.model.ai.AiSseEvents.TurnStartedEvent;

/** AI 客服轮次事件槽；抽象为接口让编排层可脱离 SseEmitter 单测。 */
public interface AiTurnEventSink {

    void turnStarted(TurnStartedEvent event);

    void content(String eventName, ContentEvent event);

    void turnCompleted(TurnCompletedEvent event);

    void turnFailed(TurnFailedEvent event);
}
