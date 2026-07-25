package com.sx.passenger.lifecycle.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passenger.lifecycle.orchestration.AccountCancellationOrchestrator;
import com.sx.passenger.lifecycle.orchestration.LifecycleParticipantResult;
import com.sx.passenger.lifecycle.orchestration.AccountCancellationOrchestrationTransaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "passenger.account-lifecycle.messaging",
        name = "enabled", havingValue = "true")
public class LifecycleResultKafkaConsumer {
    private final ObjectMapper objectMapper;
    private final AccountCancellationOrchestrationTransaction transaction;
    private final AccountCancellationOrchestrator orchestrator;

    public LifecycleResultKafkaConsumer(
            ObjectMapper objectMapper,
            AccountCancellationOrchestrationTransaction transaction,
            AccountCancellationOrchestrator orchestrator) {
        this.objectMapper = objectMapper;
        this.transaction = transaction;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(topics = "${passenger.account-lifecycle.messaging.result-topic}")
    public void consume(String payload) {
        ResultMessage message = read(payload);
        LifecycleParticipantResult result = new LifecycleParticipantResult(
                message.decision(), message.blockers(), message.result());
        transaction.applyResult(message.operationNo(), message.stepCode(), message.eventId(), result);
        orchestrator.resume(message.operationNo());
    }

    private ResultMessage read(String payload) {
        try {
            return objectMapper.readValue(payload, ResultMessage.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("生命周期Kafka结果格式错误", ex);
        }
    }

    private record ResultMessage(
            String eventId, String operationNo, String stepCode, long customerId,
            String participantCode, String decision,
            List<LifecycleParticipantResult.Blocker> blockers,
            Map<String, Object> result, LocalDateTime completedAt) {}
}
