package com.sx.wallet.lifecycle.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.wallet.lifecycle.model.WalletLifecycleParticipantResult;
import com.sx.wallet.lifecycle.service.AccountLifecycleWalletParticipantService;
import com.sx.wallet.lifecycle.service.WalletLifecycleProjectionService;
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
@ConditionalOnProperty(prefix = "wallet.lifecycle.messaging",
        name = "enabled", havingValue = "true")
public class WalletLifecycleKafkaConsumer {
    private final AccountLifecycleWalletParticipantService participant;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafka;
    private final String resultTopic;
    private final WalletLifecycleProjectionService projections;

    public WalletLifecycleKafkaConsumer(
            AccountLifecycleWalletParticipantService participant,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafka,
            WalletLifecycleProjectionService projections,
            @Value("${wallet.lifecycle.messaging.result-topic}") String resultTopic) {
        this.participant = participant;
        this.objectMapper = objectMapper;
        this.kafka = kafka;
        this.projections = projections;
        this.resultTopic = resultTopic;
    }

    @KafkaListener(topics = "${wallet.lifecycle.messaging.event-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consumeStatusEvent(String payload) {
        LifecycleStatusEvent event = readStatusEvent(payload);
        projections.apply(new com.sx.wallet.lifecycle.model.WalletLifecycleCommand(
                event.operationNo(), "WALLET_PROJECT_CANCELLED", event.customerId(),
                event.lifecycleVersion(), event.lifecycleStatus(), event.eventId(), event.updatedAt()));
    }

    @KafkaListener(topics = "${wallet.lifecycle.messaging.command-topic}")
    public void consume(String payload) {
        WalletLifecycleCommandMessage command = read(payload);
        if (!"WALLET".equals(command.targetDomain())) return;
        WalletLifecycleParticipantResult result = participant.action(command.toCommand());
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("eventId", command.eventId());
        reply.put("operationNo", command.operationNo());
        reply.put("stepCode", command.stepCode());
        reply.put("customerId", command.customerId());
        reply.put("participantCode", "WALLET");
        reply.put("decision", result.decision());
        reply.put("blockers", result.blockers());
        reply.put("result", result.result());
        reply.put("completedAt", LocalDateTime.now());
        try {
            kafka.send(resultTopic, Long.toString(command.customerId()),
                    objectMapper.writeValueAsString(reply)).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Wallet生命周期结果发布失败", ex);
        }
    }

    private WalletLifecycleCommandMessage read(String payload) {
        try {
            return objectMapper.readValue(payload, WalletLifecycleCommandMessage.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Wallet生命周期Kafka命令格式错误", ex);
        }
    }

    private LifecycleStatusEvent readStatusEvent(String payload) {
        try {
            return objectMapper.readValue(payload, LifecycleStatusEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Wallet生命周期状态事件格式错误", ex);
        }
    }

    private record LifecycleStatusEvent(String eventId, String operationNo, long customerId,
                                        long lifecycleVersion, String lifecycleStatus,
                                        LocalDateTime updatedAt) {}
}
