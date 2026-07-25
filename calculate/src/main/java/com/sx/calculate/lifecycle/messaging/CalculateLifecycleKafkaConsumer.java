package com.sx.calculate.lifecycle.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.lifecycle.model.CalculateLifecycleParticipantResult;
import com.sx.calculate.lifecycle.model.ApplyCalculateLifecycleProjectionCommand;
import com.sx.calculate.lifecycle.service.AccountLifecycleCalculateParticipantService;
import com.sx.calculate.lifecycle.service.CalculateLifecycleProjectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "calculate.lifecycle.messaging",
        name = "enabled", havingValue = "true")
public class CalculateLifecycleKafkaConsumer {
    private final AccountLifecycleCalculateParticipantService participant;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafka;
    private final String resultTopic;
    private final CalculateLifecycleProjectionService projections;

    public CalculateLifecycleKafkaConsumer(
            AccountLifecycleCalculateParticipantService participant,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafka,
            CalculateLifecycleProjectionService projections,
            @Value("${calculate.lifecycle.messaging.result-topic}") String resultTopic) {
        this.participant = participant;
        this.objectMapper = objectMapper;
        this.kafka = kafka;
        this.projections = projections;
        this.resultTopic = resultTopic;
    }

    @KafkaListener(topics = "${calculate.lifecycle.messaging.event-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consumeStatusEvent(String payload) {
        LifecycleStatusEvent event = readStatusEvent(payload);
        projections.apply(new ApplyCalculateLifecycleProjectionCommand(
                event.customerId(), 0, event.lifecycleStatus(), event.lifecycleVersion(),
                event.operationNo(), event.eventId(), event.updatedAt()));
    }

    @KafkaListener(topics = "${calculate.lifecycle.messaging.command-topic}")
    public void consume(String payload) {
        CalculateLifecycleCommandMessage command = read(payload);
        if (!"CALCULATE".equals(command.targetDomain())) return;
        CalculateLifecycleParticipantResult result = participant.action(command.toCommand());
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("eventId", command.eventId());
        reply.put("operationNo", command.operationNo());
        reply.put("stepCode", command.stepCode());
        reply.put("customerId", command.customerId());
        reply.put("participantCode", "CALCULATE");
        reply.put("decision", result.decision().name());
        reply.put("blockers", result.blockers());
        reply.put("result", result.result());
        reply.put("completedAt", LocalDateTime.now());
        try {
            kafka.send(resultTopic, Long.toString(command.customerId()),
                    objectMapper.writeValueAsString(reply)).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Calculate生命周期结果发布失败", ex);
        }
    }

    private CalculateLifecycleCommandMessage read(String payload) {
        try {
            return objectMapper.readValue(payload, CalculateLifecycleCommandMessage.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Calculate生命周期Kafka命令格式错误", ex);
        }
    }

    private LifecycleStatusEvent readStatusEvent(String payload) {
        try {
            return objectMapper.readValue(payload, LifecycleStatusEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Calculate生命周期状态事件格式错误", ex);
        }
    }

    private record LifecycleStatusEvent(String eventId, String operationNo, long customerId,
                                        long lifecycleVersion, String lifecycleStatus,
                                        LocalDateTime updatedAt) {}
}
