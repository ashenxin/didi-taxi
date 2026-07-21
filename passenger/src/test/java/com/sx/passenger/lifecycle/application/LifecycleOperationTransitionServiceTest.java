package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.*;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LifecycleOperationTransitionServiceTest {
    @Autowired LifecycleOperationTransitionService service;
    @Autowired LifecycleOperationMapper operationMapper;
    @Autowired LifecycleEventMapper eventMapper;

    @BeforeEach
    void clean() { eventMapper.delete(null); operationMapper.delete(null); }

    @Test
    void transitionsWithCasAndWritesAuditEvent() {
        LifecycleOperationEntity operation = operation();
        operationMapper.insert(operation);

        service.transition(new TransitionLifecycleOperationCommand(operation.getId(), 0,
                LifecycleOperationStatus.FENCED, LifecycleActorType.SYSTEM, null, "FENCE_CREATED",
                "trace-2", "{}", Instant.parse("2026-07-21T01:31:00Z")));

        LifecycleOperationEntity changed = operationMapper.selectById(operation.getId());
        assertThat(changed.getStatus()).isEqualTo("FENCED");
        assertThat(changed.getRowVersion()).isEqualTo(1);
        assertThat(eventMapper.selectCount(null)).isEqualTo(1);

        assertThatThrownBy(() -> service.transition(new TransitionLifecycleOperationCommand(operation.getId(), 0,
                LifecycleOperationStatus.VALIDATING, LifecycleActorType.SYSTEM, null, "STALE",
                "trace-2", "{}", Instant.parse("2026-07-21T01:32:00Z"))))
                .isInstanceOf(LifecycleOperationConflictException.class);
        assertThat(eventMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidTransitionBeforeUpdatingDatabase() {
        LifecycleOperationEntity operation = operation();
        operationMapper.insert(operation);
        assertThatThrownBy(() -> service.transition(new TransitionLifecycleOperationCommand(operation.getId(), 0,
                LifecycleOperationStatus.COMPLETED, LifecycleActorType.SYSTEM, null, "INVALID",
                null, "{}", Instant.EPOCH)))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
        assertThat(operationMapper.selectById(operation.getId()).getRowVersion()).isZero();
    }

    private static LifecycleOperationEntity operation() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 21, 9, 30);
        return new LifecycleOperationEntity().setOperationNo("ALO-transition").setCustomerId(10001L)
                .setOperationType("ACCOUNT_CANCEL").setStatus("REQUESTED").setIdempotencyKey("idem-transition")
                .setRequestHash("a".repeat(64)).setExpectedLifecycleVersion(0L).setPlanCode("account-cancel")
                .setPlanVersion(1).setPlanDigest("b".repeat(64)).setIrreversibleStarted(0)
                .setActiveBlockerCount(0).setRowVersion(0L).setRequestedAt(now).setCreatedAt(now).setUpdatedAt(now);
    }
}
