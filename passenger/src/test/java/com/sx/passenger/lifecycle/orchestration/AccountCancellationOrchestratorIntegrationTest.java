package com.sx.passenger.lifecycle.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleBlockerMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AccountCancellationOrchestratorIntegrationTest {
    private static final long CUSTOMER_ID = 96_001L;
    private static final String OPERATION_NO = "cancel-p6-integration";

    @Autowired AccountCancellationOrchestrator orchestrator;
    @Autowired AccountCancellationOrchestrationTransaction transaction;
    @Autowired LifecycleOperationMapper operations;
    @Autowired LifecycleStepMapper steps;
    @Autowired LifecycleOutboxMapper outbox;
    @Autowired LifecycleBlockerMapper blockers;
    @Autowired LifecycleEventMapper events;
    @Autowired JdbcTemplate jdbc;
    @MockBean LifecycleParticipantGateway participants;

    @BeforeEach
    void setUp() {
        outbox.delete(null);
        events.delete(null);
        blockers.delete(null);
        steps.delete(null);
        operations.delete(null);
        jdbc.update("DELETE FROM customer_phone_binding_history WHERE customer_id = ?", CUSTOMER_ID);
        jdbc.update("DELETE FROM customer WHERE id = ?", CUSTOMER_ID);
        jdbc.update("""
                INSERT INTO customer
                    (id, phone, status, lifecycle_status, lifecycle_version, auth_epoch,
                     current_lifecycle_operation_no, is_deleted)
                VALUES (?, ?, 0, 'CANCELLING', 1, 8, ?, 0)
                """, CUSTOMER_ID, "13900960001", OPERATION_NO);
        when(participants.executeCheck(any())).thenReturn(
                new LifecycleParticipantResult("PASS", List.of(), Map.of()));
    }

    @Test
    void persistsAsyncCommandBeforeFinalizingAndAcceptsIdempotentResult() {
        LifecycleOperationEntity operation = operation();
        operations.insert(operation);
        insertStep(operation.getId(), "ORDER_FINAL_CHECK", "ORDER",
                "PRECONDITION", "SYNC_CHECK", "REQUIRED", 100);
        insertStep(operation.getId(), "IDENTITY_FINAL_CHECK", "IDENTITY",
                "PRECONDITION", "LOCAL_TRANSACTION", "REQUIRED", 100);
        insertStep(operation.getId(), "WALLET_CLOSE_AUTO_PAY", "WALLET",
                "ACTION", "ASYNC_COMMAND", "REQUIRED", 300);
        insertStep(operation.getId(), "ACCOUNT_FINALIZE_CANCEL", "ACCOUNT",
                "FINALIZE", "LOCAL_TRANSACTION", "REQUIRED", 600);

        orchestrator.resume(OPERATION_NO);

        LifecycleOperationEntity executing = operations.selectById(operation.getId());
        LifecycleStepEntity wallet = findStep(operation.getId(), "WALLET_CLOSE_AUTO_PAY");
        assertThat(executing.getStatus()).isEqualTo("EXECUTING");
        assertThat(executing.getIrreversibleStarted()).isEqualTo(1);
        assertThat(wallet.getStatus()).isEqualTo("RUNNING");
        assertThat(outbox.selectCount(new LambdaQueryWrapper<LifecycleOutboxEntity>()
                .eq(LifecycleOutboxEntity::getEventId, wallet.getCommandEventId()))).isOne();

        LifecycleParticipantResult pass =
                new LifecycleParticipantResult("PASS", List.of(), Map.of("closed", true));
        transaction.applyResult(OPERATION_NO, wallet.getStepCode(), wallet.getCommandEventId(), pass);
        transaction.applyResult(OPERATION_NO, wallet.getStepCode(), wallet.getCommandEventId(), pass);
        orchestrator.resume(OPERATION_NO);

        LifecycleOperationEntity completed = operations.selectById(operation.getId());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getAppliedLifecycleVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_status FROM customer WHERE id = ?",
                String.class, CUSTOMER_ID)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM customer WHERE id = ?",
                Integer.class, CUSTOMER_ID)).isEqualTo(1);
    }

    private LifecycleOperationEntity operation() {
        LocalDateTime now = LocalDateTime.now();
        return new LifecycleOperationEntity()
                .setOperationNo(OPERATION_NO).setCustomerId(CUSTOMER_ID)
                .setOperationType("ACCOUNT_CANCEL").setStatus("FENCED")
                .setIdempotencyKey("p6-idem").setRequestHash("a".repeat(64))
                .setExpectedLifecycleVersion(0L).setAppliedLifecycleVersion(1L)
                .setPlanCode("account-cancel").setPlanVersion(1).setPlanDigest("b".repeat(64))
                .setIrreversibleStarted(0).setRestrictedAuthEpoch(8L)
                .setActiveBlockerCount(0).setRowVersion(1L)
                .setRequestedAt(now).setFencedAt(now).setCreatedAt(now).setUpdatedAt(now);
    }

    private void insertStep(long operationId, String code, String participant, String phase,
                            String mode, String criticality, int sequence) {
        LocalDateTime now = LocalDateTime.now();
        steps.insert(new LifecycleStepEntity().setOperationId(operationId).setStepCode(code)
                .setParticipantCode(participant).setPhase(phase).setExecutionMode(mode)
                .setCriticality(criticality).setSequenceNo(sequence).setStatus("PENDING")
                .setAttemptCount(0).setMaxRetryCount(3).setRetryInitialSeconds(1)
                .setTimeoutSeconds(30).setStepConfig("{}").setCreatedAt(now).setUpdatedAt(now));
    }

    private LifecycleStepEntity findStep(long operationId, String code) {
        return steps.selectOne(new LambdaQueryWrapper<LifecycleStepEntity>()
                .eq(LifecycleStepEntity::getOperationId, operationId)
                .eq(LifecycleStepEntity::getStepCode, code));
    }
}
