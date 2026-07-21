package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.persistence.mapper.*;
import com.sx.passenger.lifecycle.plan.LifecyclePlanRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LifecycleSnapshotStoreIntegrationTest {
    @Autowired LifecycleSnapshotStore store;
    @Autowired LifecyclePlanRegistry registry;
    @Autowired TransactionTemplate transactions;
    @Autowired LifecycleOperationMapper operationMapper;
    @Autowired LifecycleStepMapper stepMapper;
    @Autowired LifecycleEventMapper eventMapper;
    @Autowired LifecycleOutboxMapper outboxMapper;

    @BeforeEach
    void clean() {
        outboxMapper.delete(null); eventMapper.delete(null); stepMapper.delete(null); operationMapper.delete(null);
    }

    @Test
    void persistsOperationStepsEventAndOutboxInCallingTransaction() {
        LifecycleRuntimeSnapshot snapshot = snapshot("idem-1");
        transactions.executeWithoutResult(status -> store.persistNew(snapshot));

        assertThat(operationMapper.selectCount(null)).isEqualTo(1);
        assertThat(stepMapper.selectCount(null)).isEqualTo(12);
        assertThat(eventMapper.selectCount(null)).isEqualTo(1);
        assertThat(outboxMapper.selectCount(null)).isEqualTo(1);
        assertThat(snapshot.steps()).allMatch(step -> step.getOperationId().equals(snapshot.operation().getId()));
        assertThat(snapshot.requestedOutbox().getOperationId()).isEqualTo(snapshot.operation().getId());
    }

    @Test
    void requiresOuterTransactionAndRollsBackAllRuntimeRows() {
        assertThatThrownBy(() -> store.persistNew(snapshot("no-transaction")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            store.persistNew(snapshot("rollback"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(operationMapper.selectCount(null)).isZero();
        assertThat(stepMapper.selectCount(null)).isZero();
        assertThat(eventMapper.selectCount(null)).isZero();
        assertThat(outboxMapper.selectCount(null)).isZero();
    }

    private LifecycleRuntimeSnapshot snapshot(String idempotencyKey) {
        return new LifecycleRuntimeSnapshotFactory(registry, new UuidLifecycleIdentifierGenerator(), new LifecycleJson())
                .create(new CreateLifecycleSnapshotCommand(10001L, LifecycleOperationType.ACCOUNT_CANCEL,
                        idempotencyKey, "a".repeat(64), 0L, LifecycleActorType.CUSTOMER, "10001",
                        "trace-1", "{}", Instant.parse("2026-07-21T01:30:00Z")));
    }
}
