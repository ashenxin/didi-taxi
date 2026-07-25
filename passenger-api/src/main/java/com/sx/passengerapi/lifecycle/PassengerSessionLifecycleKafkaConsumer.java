package com.sx.passengerapi.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "passenger.account-lifecycle.messaging",
        name = "enabled", havingValue = "true")
public class PassengerSessionLifecycleKafkaConsumer {
    private static final String STEP = "SESSION_CLOSE_WS";
    private final PassengerWsSessionRegistry sessions;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafka;
    private final String resultTopic;

    public PassengerSessionLifecycleKafkaConsumer(
            PassengerWsSessionRegistry sessions,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafka,
            @Value("${passenger.account-lifecycle.messaging.result-topic}") String resultTopic) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.kafka = kafka;
        this.resultTopic = resultTopic;
    }

    @KafkaListener(topics = "${passenger.account-lifecycle.messaging.command-topic}")
    public void consume(String payload) {
        Command command = read(payload);
        if (!"SESSION".equals(command.targetDomain()) || !STEP.equals(command.stepCode())) return;
        sessions.closeCustomerSessions(command.customerId(), "account_cancelled");
        Result result = new Result(command.eventId(), command.operationNo(), command.stepCode(),
                command.customerId(), "SESSION", "PASS", List.of(),
                Map.of("nodeSessionClosed", true), LocalDateTime.now());
        try {
            kafka.send(resultTopic, Long.toString(command.customerId()),
                    objectMapper.writeValueAsString(result)).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Passenger WS关闭结果发布失败", ex);
        }
    }

    private Command read(String payload) {
        try {
            return objectMapper.readValue(payload, Command.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Passenger WS生命周期命令格式错误", ex);
        }
    }

    private record Command(String eventId, String operationNo, String stepCode,
                           long customerId, long lifecycleVersion,
                           String targetLifecycleStatus, String targetDomain,
                           LocalDateTime requestedAt) {}

    private record Result(String eventId, String operationNo, String stepCode,
                          long customerId, String participantCode, String decision,
                          List<Object> blockers, Map<String, Object> result,
                          LocalDateTime completedAt) {}
}
