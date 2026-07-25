package com.sx.calculate.lifecycle;

import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleEventInboxMapper;
import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleProjectionMapper;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleProjectionConflictException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleUnknownException;
import com.sx.calculate.lifecycle.model.ApplyCalculateLifecycleProjectionCommand;
import com.sx.calculate.lifecycle.model.CalculateLifecycleStatus;
import com.sx.calculate.lifecycle.service.CalculateLifecycleProjectionService;
import com.sx.calculate.lifecycle.service.ProjectionApplyResult;
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
class CalculateLifecycleProjectionServiceTest {

    @Autowired private CalculateLifecycleProjectionService service;
    @Autowired private CalculateAccountLifecycleProjectionMapper projections;
    @Autowired private CalculateAccountLifecycleEventInboxMapper events;

    @BeforeEach
    void clean() {
        projections.delete(null);
        events.delete(null);
    }

    @Test
    void activeSeedHigherVersionAndExactReplayAreMonotonic() {
        assertThat(service.seedActive(101L, "seed-101", LocalDateTime.now()))
                .isEqualTo(ProjectionApplyResult.APPLIED);
        var cancelling = command(101L, 0, CalculateLifecycleStatus.CANCELLING,
                1, "op-101", "event-101-1");

        assertThat(service.apply(cancelling)).isEqualTo(ProjectionApplyResult.APPLIED);
        assertThat(service.apply(cancelling)).isEqualTo(ProjectionApplyResult.REPLAYED);
        assertThat(projections.selectById(101L).getLifecycleStatus()).isEqualTo("CANCELLING");
        assertThat(projections.selectById(101L).getLifecycleVersion()).isEqualTo(1L);
        assertThat(events.selectCount(null)).isEqualTo(2L);
    }

    @Test
    void missingProjectionFailsClosedAndTransactionRollsBackClaimedEvent() {
        assertThatThrownBy(() -> service.apply(command(102L, 0,
                CalculateLifecycleStatus.CANCELLING, 1, "op-102", "event-102-1")))
                .isInstanceOf(CalculateLifecycleUnknownException.class);

        assertThat(events.selectById("event-102-1")).isNull();
    }

    @Test
    void staleVersionAndSameVersionDifferentContentConflict() {
        service.seedActive(103L, "seed-103", LocalDateTime.now());
        service.apply(command(103L, 0, CalculateLifecycleStatus.CANCELLING,
                2, "op-103", "event-103-2"));

        assertThatThrownBy(() -> service.apply(command(103L, 0,
                CalculateLifecycleStatus.CANCELLING, 1, "op-103", "event-103-1")))
                .isInstanceOf(CalculateLifecycleProjectionConflictException.class);
        assertThatThrownBy(() -> service.apply(command(103L, 0,
                CalculateLifecycleStatus.CANCELLING, 2, "op-other", "event-103-other")))
                .isInstanceOf(CalculateLifecycleProjectionConflictException.class);
    }

    @Test
    void sourceEventIdCannotBeReusedWithDifferentPayload() {
        service.seedActive(104L, "seed-104", LocalDateTime.now());
        service.apply(command(104L, 0, CalculateLifecycleStatus.CANCELLING,
                1, "op-104", "event-permanent"));

        assertThatThrownBy(() -> service.apply(command(104L, 1,
                CalculateLifecycleStatus.CANCELLED, 2, "op-104", "event-permanent")))
                .isInstanceOf(CalculateLifecycleProjectionConflictException.class);
    }

    @Test
    void cancelledProjectionIsTerminal() {
        service.seedActive(105L, "seed-105", LocalDateTime.now());
        service.apply(command(105L, 1, CalculateLifecycleStatus.CANCELLED,
                1, "op-105", "event-105-1"));

        assertThatThrownBy(() -> service.apply(command(105L, 0,
                CalculateLifecycleStatus.ACTIVE, 2, null, "event-105-2")))
                .isInstanceOf(CalculateLifecycleProjectionConflictException.class);
    }

    private static ApplyCalculateLifecycleProjectionCommand command(
            long customerId, int businessStatus, CalculateLifecycleStatus status,
            long version, String operationNo, String sourceEventId) {
        return new ApplyCalculateLifecycleProjectionCommand(customerId, businessStatus,
                status.name(), version, operationNo, sourceEventId, LocalDateTime.now());
    }
}
