package com.sx.passenger.lifecycle.persistence;

import com.sx.passenger.lifecycle.persistence.entity.*;
import com.sx.passenger.lifecycle.persistence.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LifecycleMybatisMappingTest {

    @Autowired LifecycleOperationMapper operationMapper;
    @Autowired LifecycleStepMapper stepMapper;
    @Autowired LifecycleBlockerMapper blockerMapper;
    @Autowired LifecycleEventMapper eventMapper;
    @Autowired LifecycleOutboxMapper outboxMapper;
    @Autowired CustomerPhoneBindingHistoryMapper phoneHistoryMapper;

    @Test
    void mapsAllReviewedRuntimeTables() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 21, 9, 30);
        LifecycleOperationEntity operation = new LifecycleOperationEntity()
                .setOperationNo("ALO-1").setCustomerId(10001L).setOperationType("ACCOUNT_CANCEL")
                .setStatus("REQUESTED").setIdempotencyKey("idem-1").setRequestHash("a".repeat(64))
                .setExpectedLifecycleVersion(0L).setPlanCode("account-cancel").setPlanVersion(1)
                .setPlanDigest("b".repeat(64)).setIrreversibleStarted(0).setActiveBlockerCount(0)
                .setRowVersion(0L).setRequestContext("{\"device\":\"test\"}")
                .setRequestedAt(now).setCreatedAt(now).setUpdatedAt(now);
        assertThat(operationMapper.insert(operation)).isEqualTo(1);

        LifecycleStepEntity step = new LifecycleStepEntity()
                .setOperationId(operation.getId()).setStepCode("ORDER_FINAL_CHECK").setParticipantCode("ORDER")
                .setPhase("PRECONDITION").setExecutionMode("SYNC_CHECK").setCriticality("REQUIRED")
                .setSequenceNo(100).setStatus("PENDING").setAttemptCount(0).setMaxRetryCount(3)
                .setRetryInitialSeconds(5).setTimeoutSeconds(10).setStepConfig("{\"timeoutSeconds\":10}")
                .setCreatedAt(now).setUpdatedAt(now);
        stepMapper.insert(step);

        LifecycleBlockerEntity blocker = new LifecycleBlockerEntity()
                .setOperationId(operation.getId()).setStepId(step.getId()).setDomainCode("ORDER")
                .setBlockerKey("UNSETTLED_ORDER:T1").setBlockerType("UNSETTLED_ORDER")
                .setStatus("ACTIVE").setResolutionActions("[\"PAY_DEBT\"]")
                .setDetectedAt(now).setLastConfirmedAt(now).setCreatedAt(now).setUpdatedAt(now);
        blockerMapper.insert(blocker);

        LifecycleEventEntity event = new LifecycleEventEntity()
                .setEventId("EVT-1").setOperationId(operation.getId()).setCustomerId(10001L)
                .setEventType("LIFECYCLE_OPERATION_REQUESTED").setToStatus("REQUESTED")
                .setActorType("CUSTOMER").setTraceId("trace-1").setPayloadSnapshot("{}")
                .setCreatedAt(now);
        eventMapper.insert(event);

        LifecycleOutboxEntity outbox = new LifecycleOutboxEntity()
                .setEventId("EVT-2").setOperationId(operation.getId()).setAggregateType("ACCOUNT_LIFECYCLE")
                .setAggregateId("ALO-1").setEventType("LIFECYCLE_OPERATION_REQUESTED")
                .setCausationEventId("EVT-1").setTraceId("trace-1").setTopic("account.lifecycle.requested.v1")
                .setPartitionKey("10001").setPayload("{}").setStatus("PENDING").setRetryCount(0)
                .setMaxRetryCount(10).setNextRetryAt(now).setCreatedAt(now).setUpdatedAt(now);
        outboxMapper.insert(outbox);

        CustomerPhoneBindingHistoryEntity history = new CustomerPhoneBindingHistoryEntity()
                .setCustomerId(10001L).setBindingVersion(1L).setStatus("ACTIVE")
                .setPhoneCiphertext(new byte[]{1, 2}).setPhoneIdentityHash("c".repeat(64))
                .setHashKeyVersion("v1").setChangeReason("MIGRATION").setValidFrom(now)
                .setCreatedAt(now).setUpdatedAt(now);
        phoneHistoryMapper.insert(history);

        assertThat(operationMapper.selectById(operation.getId()).getPlanDigest()).isEqualTo("b".repeat(64));
        assertThat(stepMapper.selectById(step.getId()).getStepConfig()).contains("timeoutSeconds");
        assertThat(blockerMapper.selectById(blocker.getId()).getResolutionActions()).contains("PAY_DEBT");
        assertThat(eventMapper.selectById(event.getId()).getTraceId()).isEqualTo("trace-1");
        assertThat(outboxMapper.selectById(outbox.getId()).getCausationEventId()).isEqualTo("EVT-1");
        assertThat(phoneHistoryMapper.selectById(history.getId()).getPhoneCiphertext()).containsExactly(1, 2);
        assertThat(Arrays.stream(LifecycleOperationEntity.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .contains("planCode", "planVersion", "planDigest").doesNotContain("planSnapshot");
        assertThat(Arrays.stream(LifecycleStepEntity.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .contains("stepConfig");
    }
}
