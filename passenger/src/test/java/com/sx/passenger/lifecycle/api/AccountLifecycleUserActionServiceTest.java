package com.sx.passenger.lifecycle.api;

import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.LifecycleStatusOutboxAppender;
import com.sx.passenger.lifecycle.domain.InvalidLifecycleTransitionException;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleBlockerMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import com.sx.passenger.model.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountLifecycleUserActionServiceTest {
    private final LifecycleOperationMapper operations = mock(LifecycleOperationMapper.class);
    private final LifecycleStepMapper steps = mock(LifecycleStepMapper.class);
    private final LifecycleBlockerMapper blockers = mock(LifecycleBlockerMapper.class);
    private final LifecycleEventMapper events = mock(LifecycleEventMapper.class);
    private final CustomerEntityMapper customers = mock(CustomerEntityMapper.class);
    private final LifecycleStatusOutboxAppender statusOutboxes =
            mock(LifecycleStatusOutboxAppender.class);
    private AccountLifecycleUserActionService service;

    @BeforeEach
    void setUp() {
        service = new AccountLifecycleUserActionService(
                operations, steps, blockers, events, customers, statusOutboxes);
    }

    @Test
    void doesNotRevealOrMutateOperationOwnedByAnotherCustomer() {
        when(operations.findByOperationNoForUpdate("LC-1"))
                .thenReturn(operation(8L, "FENCED", 0));

        assertThatThrownBy(() -> service.abort(7L, "LC-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");

        verifyNoInteractions(customers, steps, blockers, events, statusOutboxes);
    }

    @Test
    void refusesAbortAfterIrreversibleWorkStarted() {
        when(operations.findByOperationNoForUpdate("LC-1"))
                .thenReturn(operation(7L, "BLOCKED", 1));

        assertThatThrownBy(() -> service.abort(7L, "LC-1"))
                .isInstanceOf(InvalidLifecycleTransitionException.class);

        verifyNoInteractions(customers, steps, blockers, events, statusOutboxes);
    }

    @Test
    void publishesActiveProjectionAfterReversibleAbort() {
        LifecycleOperationEntity operation = operation(7L, "MANUAL_REVIEW", 0);
        Customer customer = new Customer().setId(7L).setLifecycleVersion(13L).setAuthEpoch(9L);
        when(operations.findByOperationNoForUpdate("LC-1")).thenReturn(operation);
        when(customers.abortAccountCancellation(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("LC-1"),
                org.mockito.ArgumentMatchers.eq(12L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        when(customers.selectById(7L)).thenReturn(customer);
        when(operations.abortCancellationCas(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        when(events.insert(org.mockito.ArgumentMatchers.any(
                com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity.class)))
                .thenReturn(1);

        service.abort(7L, "LC-1");

        verify(statusOutboxes).append(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("LC-1"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(13L),
                org.mockito.ArgumentMatchers.eq("ACTIVE"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
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
