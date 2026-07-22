package com.sx.order.lifecycle;

import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleEventInboxMapper;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.model.OrderLifecycleStatus;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import com.sx.order.lifecycle.service.ProjectionApplyResult;
import com.sx.order.lifecycle.exception.AccountLifecycleUnknownException;
import com.sx.order.lifecycle.exception.OrderLifecycleProjectionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static ApplyOrderLifecycleProjectionCommand command(
            long customerId, int businessStatus, OrderLifecycleStatus status, long version,
            String operationNo, String eventId) {
        return new ApplyOrderLifecycleProjectionCommand(customerId, businessStatus,
                status.name(), version, operationNo, eventId, LocalDateTime.now());
    }
}
