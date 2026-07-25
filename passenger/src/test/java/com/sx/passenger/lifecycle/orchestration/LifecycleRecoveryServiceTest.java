package com.sx.passenger.lifecycle.orchestration;

import com.sx.passenger.lifecycle.job.LifecycleJobBatchResult;
import com.sx.passenger.lifecycle.job.LifecycleJobProperties;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LifecycleRecoveryServiceTest {
    private LifecycleOperationMapper operations;
    private LifecycleStepMapper steps;
    private AccountCancellationOrchestrationTransaction transaction;
    private AccountCancellationOrchestrator orchestrator;
    private LifecycleParticipantGateway participants;
    private LifecycleRecoveryService recovery;

    @BeforeEach
    void setUp() {
        operations = mock(LifecycleOperationMapper.class);
        steps = mock(LifecycleStepMapper.class);
        transaction = mock(AccountCancellationOrchestrationTransaction.class);
        orchestrator = mock(AccountCancellationOrchestrator.class);
        participants = mock(LifecycleParticipantGateway.class);
        recovery = new LifecycleRecoveryService(
                operations, steps, transaction, orchestrator, participants,
                new LifecycleJobProperties());
    }

    @Test
    void operationFailureIsCountedWithoutStoppingBatch() {
        LifecycleOperationEntity operation = new LifecycleOperationEntity()
                .setOperationNo("op-recovery-failed")
                .setStatus("EXECUTING");
        when(operations.findDueForRecovery(org.mockito.ArgumentMatchers.any(), 
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(operation));
        doThrow(new IllegalStateException("participant unavailable"))
                .when(orchestrator).resume("op-recovery-failed");

        LifecycleJobBatchResult result = recovery.recoverDueOperations(10);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.hasTechnicalFailure()).isTrue();
    }

    @Test
    void timedOutQueryFailureIsCountedAndRemainsRecoverable() {
        LifecycleStepEntity step = new LifecycleStepEntity()
                .setId(7L).setStepCode("WALLET_CLOSE_AUTO_PAY")
                .setParticipantCode("WALLET");
        when(steps.findTimedOutRunning(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(step));
        LifecycleParticipantCommand command = new LifecycleParticipantCommand(
                "event-7", "op-7", "WALLET_CLOSE_AUTO_PAY", 7L, 1L,
                "CANCELLING", "WALLET", LocalDateTime.now());
        when(transaction.prepareTimedOutQuery(7L)).thenReturn(command);
        when(participants.queryResult("WALLET", "op-7", "WALLET_CLOSE_AUTO_PAY"))
                .thenThrow(new IllegalStateException("wallet unavailable"));

        LifecycleJobBatchResult result = recovery.recoverTimedOutSteps(10);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.succeeded()).isZero();
    }
}
