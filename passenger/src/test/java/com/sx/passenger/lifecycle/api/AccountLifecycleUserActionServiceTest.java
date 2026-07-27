package com.sx.passenger.lifecycle.api;

import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.domain.InvalidLifecycleTransitionException;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleBlockerMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountLifecycleUserActionServiceTest {
    private final LifecycleOperationMapper operations = mock(LifecycleOperationMapper.class);
    private final LifecycleStepMapper steps = mock(LifecycleStepMapper.class);
    private final LifecycleBlockerMapper blockers = mock(LifecycleBlockerMapper.class);
    private final LifecycleEventMapper events = mock(LifecycleEventMapper.class);
    private final CustomerEntityMapper customers = mock(CustomerEntityMapper.class);
    private AccountLifecycleUserActionService service;

    @BeforeEach
    void setUp() {
        service = new AccountLifecycleUserActionService(
                operations, steps, blockers, events, customers);
    }

    @Test
    void doesNotRevealOrMutateOperationOwnedByAnotherCustomer() {
        when(operations.findByOperationNoForUpdate("LC-1"))
                .thenReturn(operation(8L, "FENCED", 0));

        assertThatThrownBy(() -> service.abort(7L, "LC-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");

        verifyNoInteractions(customers, steps, blockers, events);
    }

    @Test
    void refusesAbortAfterIrreversibleWorkStarted() {
        when(operations.findByOperationNoForUpdate("LC-1"))
                .thenReturn(operation(7L, "BLOCKED", 1));

        assertThatThrownBy(() -> service.abort(7L, "LC-1"))
                .isInstanceOf(InvalidLifecycleTransitionException.class);

        verifyNoInteractions(customers, steps, blockers, events);
    }

    private static LifecycleOperationEntity operation(
            long customerId, String status, int irreversibleStarted) {
        return new LifecycleOperationEntity()
                .setId(1L)
                .setOperationNo("LC-1")
                .setCustomerId(customerId)
                .setOperationType("ACCOUNT_CANCEL")
                .setStatus(status)
                .setIrreversibleStarted(irreversibleStarted)
                .setAppliedLifecycleVersion(12L)
                .setRowVersion(1L);
    }
}
