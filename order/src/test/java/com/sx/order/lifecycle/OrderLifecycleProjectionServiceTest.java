package com.sx.order.lifecycle;

import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleEventInboxMapper;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.model.OrderAccountLifecycleEventInbox;
import com.sx.order.lifecycle.model.OrderLifecycleStatus;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import com.sx.order.lifecycle.service.ProjectionApplyResult;
import com.sx.order.lifecycle.exception.AccountLifecycleUnknownException;
import com.sx.order.lifecycle.exception.OrderLifecycleProjectionConflictException;
import com.sx.order.lifecycle.metrics.OrderLifecycleMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OrderLifecycleProjectionServiceTest {

    @Autowired private OrderLifecycleProjectionService service;
    @Autowired private OrderAccountLifecycleProjectionMapper mapper;
    @Autowired private OrderAccountLifecycleEventInboxMapper eventInboxMapper;

    @BeforeEach
    void clean() {
        mapper.delete(null);
        eventInboxMapper.delete(null);
    }

    @Test
    void activeSeedThenHigherVersionTransitionIsAppliedAndExactReplayIsIdempotent() {
        assertThat(service.seedActive(10001L, "seed-10001", LocalDateTime.now()))
                .isEqualTo(ProjectionApplyResult.APPLIED);
        var cancelling = command(10001L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-10001", "event-10001-1");

        assertThat(service.apply(cancelling)).isEqualTo(ProjectionApplyResult.APPLIED);
        assertThat(service.apply(cancelling)).isEqualTo(ProjectionApplyResult.REPLAYED);
        assertThat(mapper.selectById(10001L).getLifecycleStatus()).isEqualTo("CANCELLING");
        assertThat(mapper.selectById(10001L).getLifecycleVersion()).isEqualTo(1L);
    }

    @Test
    void missingNonActiveProjectionAndConflictingVersionsFailClosed() {
        assertThatThrownBy(() -> service.apply(command(10002L, 0,
                OrderLifecycleStatus.CANCELLING, 1, "op-10002", "event-10002-1")))
                .isInstanceOf(AccountLifecycleUnknownException.class);

        assertThatThrownBy(() -> service.apply(command(10002L, 0,
                OrderLifecycleStatus.ACTIVE, 2, null, "event-10002-active")))
                .isInstanceOf(AccountLifecycleUnknownException.class);

        service.seedActive(10003L, "seed-10003", LocalDateTime.now());
        service.apply(command(10003L, 0, OrderLifecycleStatus.CANCELLING, 2,
                "op-10003", "event-10003-current"));
        assertThatThrownBy(() -> service.apply(command(10003L, 0,
                OrderLifecycleStatus.CANCELLING, 1, "op-10003", "event-10003-1")))
                .isInstanceOf(OrderLifecycleProjectionConflictException.class);
        assertThatThrownBy(() -> service.apply(command(10003L, 0,
                OrderLifecycleStatus.CANCELLING, 2, "op-other", "event-10003-2")))
                .isInstanceOf(OrderLifecycleProjectionConflictException.class);
    }

    @Test
    void cancelledProjectionIsTerminalEvenWhenIncomingVersionIsHigher() {
        service.seedActive(10004L, "seed-10004", LocalDateTime.now());
        service.apply(command(10004L, 1, OrderLifecycleStatus.CANCELLED, 1,
                "op-10004", "event-10004-1"));

        assertThatThrownBy(() -> service.apply(command(10004L, 0,
                OrderLifecycleStatus.ACTIVE, 2, null, "event-10004-2")))
                .isInstanceOf(OrderLifecycleProjectionConflictException.class);
    }

    @Test
    void sourceEventIdCannotBeReusedAfterProjectionHasAdvanced() {
        service.seedActive(10005L, "seed-10005", LocalDateTime.now());
        service.apply(command(10005L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-10005", "event-permanent"));
        service.apply(command(10005L, 0, OrderLifecycleStatus.CANCELLING, 2,
                "op-10005", "event-next"));

        assertThatThrownBy(() -> service.apply(command(10005L, 1,
                OrderLifecycleStatus.CANCELLED, 3, "op-10005", "event-permanent")))
                .isInstanceOf(OrderLifecycleProjectionConflictException.class);
    }

    @Test
    void sameSourceEventCanBeAppliedConcurrentlyWithoutDuplicateFailure() throws Exception {
        service.seedActive(10006L, "seed-10006", LocalDateTime.now());
        var command = command(10006L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-10006", "event-10006-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ProjectionApplyResult> task = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return service.apply(command);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(executor.submit(task), executor.submit(task));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ProjectionApplyResult.APPLIED,
                            ProjectionApplyResult.REPLAYED);
        }

        assertThat(eventInboxMapper.selectCount(null)).isEqualTo(2L);
        assertThat(mapper.selectById(10006L).getLifecycleVersion()).isEqualTo(1L);
    }

    @Test
    void eventIsClaimedBeforeAProjectionGapIsReported() {
        OrderAccountLifecycleProjectionMapper projections =
                mock(OrderAccountLifecycleProjectionMapper.class);
        OrderAccountLifecycleEventInboxMapper events =
                mock(OrderAccountLifecycleEventInboxMapper.class);
        OrderLifecycleProjectionService isolated = new OrderLifecycleProjectionService(
                projections, events, mock(OrderLifecycleMetrics.class));
        when(events.insert(any(OrderAccountLifecycleEventInbox.class))).thenReturn(1);
        var command = command(10007L, 0, OrderLifecycleStatus.CANCELLING, 1,
                "op-10007", "event-10007-1");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> isolated.applyUnderLock(command))
                    .isInstanceOf(AccountLifecycleUnknownException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(events).insert(any(OrderAccountLifecycleEventInbox.class));
    }

    private static ApplyOrderLifecycleProjectionCommand command(
            long customerId, int businessStatus, OrderLifecycleStatus status, long version,
            String operationNo, String eventId) {
        return new ApplyOrderLifecycleProjectionCommand(customerId, businessStatus,
                status.name(), version, operationNo, eventId, LocalDateTime.now());
    }
}
