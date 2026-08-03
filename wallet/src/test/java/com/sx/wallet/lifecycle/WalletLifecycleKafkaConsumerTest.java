package com.sx.wallet.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sx.wallet.lifecycle.messaging.WalletLifecycleKafkaConsumer;
import com.sx.wallet.lifecycle.model.ApplyWalletLifecycleProjectionCommand;
import com.sx.wallet.lifecycle.service.AccountLifecycleWalletParticipantService;
import com.sx.wallet.lifecycle.service.WalletLifecycleProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class WalletLifecycleKafkaConsumerTest {

    @Test
    void initialActiveEventCreatesProjectionInsteadOfUsingTransitionPath() {
        WalletLifecycleProjectionService projections = mock(WalletLifecycleProjectionService.class);
        WalletLifecycleKafkaConsumer consumer = new WalletLifecycleKafkaConsumer(
                mock(AccountLifecycleWalletParticipantService.class),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(KafkaTemplate.class), projections, "account.lifecycle.result.v1");

        consumer.consumeStatusEvent("""
                {"eventId":"register-10014","operationNo":null,"customerId":10014,
                 "lifecycleVersion":0,"lifecycleStatus":"ACTIVE",
                 "updatedAt":"2026-08-01T16:30:00"}
                """);

        verify(projections).seedActive(10014L, "register-10014",
                LocalDateTime.of(2026, 8, 1, 16, 30));
        verifyNoMoreInteractions(projections);
    }

    @Test
    void completedPhoneChangeAppliesHigherActiveVersionWithNoCurrentOperation() {
        WalletLifecycleProjectionService projections = mock(WalletLifecycleProjectionService.class);
        WalletLifecycleKafkaConsumer consumer = new WalletLifecycleKafkaConsumer(
                mock(AccountLifecycleWalletParticipantService.class),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(KafkaTemplate.class), projections, "account.lifecycle.result.v1");
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 1, 16, 31);

        consumer.consumeStatusEvent("""
                {"eventId":"phone-change-10014","operationNo":null,"customerId":10014,
                 "lifecycleVersion":3,"lifecycleStatus":"ACTIVE",
                 "updatedAt":"2026-08-01T16:31:00"}
                """);

        verify(projections).apply(new ApplyWalletLifecycleProjectionCommand(
                10014L, 0, "ACTIVE", 3L, null, "phone-change-10014", updatedAt));
        verifyNoMoreInteractions(projections);
    }
}
