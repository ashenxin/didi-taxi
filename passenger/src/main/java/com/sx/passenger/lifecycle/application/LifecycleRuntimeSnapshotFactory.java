package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleOperationStatus;
import com.sx.passenger.lifecycle.domain.LifecycleStepStatus;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.plan.LifecyclePlanRegistry;
import com.sx.passenger.lifecycle.plan.LifecycleStepDefinition;
import com.sx.passenger.lifecycle.plan.ValidatedLifecyclePlan;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LifecycleRuntimeSnapshotFactory {
    private static final String REQUESTED_EVENT = "LIFECYCLE_OPERATION_REQUESTED";
    private final LifecyclePlanRegistry plans;
    private final LifecycleIdentifierGenerator identifiers;
    private final LifecycleJson json;

    public LifecycleRuntimeSnapshotFactory(LifecyclePlanRegistry plans,
                                           LifecycleIdentifierGenerator identifiers,
                                           LifecycleJson json) {
        this.plans = plans;
        this.identifiers = identifiers;
        this.json = json;
    }

    public LifecycleRuntimeSnapshot create(CreateLifecycleSnapshotCommand command) {
        validate(command);
        ValidatedLifecyclePlan plan = plans.activePlan(command.operationType());
        LocalDateTime now = LocalDateTime.ofInstant(command.requestedAt(), ZoneOffset.UTC);
        String operationNo = identifiers.nextOperationNo();
        LifecycleOperationEntity operation = new LifecycleOperationEntity()
                .setOperationNo(operationNo).setCustomerId(command.customerId())
                .setOperationType(command.operationType().name()).setStatus(LifecycleOperationStatus.REQUESTED.name())
                .setIdempotencyKey(command.idempotencyKey()).setRequestHash(command.requestHash())
                .setExpectedLifecycleVersion(command.expectedLifecycleVersion())
                .setPlanCode(plan.code()).setPlanVersion(plan.version()).setPlanDigest(plan.digest())
                .setIrreversibleStarted(0).setActiveBlockerCount(0).setRowVersion(0L)
                .setRequestContext(command.sanitizedRequestContextJson()).setRequestedAt(now)
                .setCreatedAt(now).setUpdatedAt(now);

        List<LifecycleStepEntity> steps = new ArrayList<>(plan.steps().size());
        for (LifecycleStepDefinition definition : plan.steps()) {
            steps.add(new LifecycleStepEntity()
                    .setStepCode(definition.code()).setParticipantCode(definition.participant())
                    .setPhase(definition.phase()).setExecutionMode(definition.executionMode())
                    .setCriticality(definition.criticality()).setSequenceNo(definition.sequence())
                    .setStatus(LifecycleStepStatus.PENDING.name()).setAttemptCount(0)
                    .setMaxRetryCount(definition.retry().maxAttempts())
                    .setRetryInitialSeconds(definition.retry().initialIntervalSeconds())
                    .setTimeoutSeconds(definition.timeoutSeconds()).setStepConfig(json.write(definition))
                    .setCreatedAt(now).setUpdatedAt(now));
        }

        String auditEventId = identifiers.nextEventId();
        LifecycleEventEntity event = new LifecycleEventEntity()
                .setEventId(auditEventId).setCustomerId(command.customerId()).setEventType(REQUESTED_EVENT)
                .setToStatus(LifecycleOperationStatus.REQUESTED.name()).setActorType(command.actorType().name())
                .setActorId(command.actorId()).setTraceId(command.traceId()).setPayloadSnapshot("{}")
                .setCreatedAt(now);

        String outboxEventId = identifiers.nextEventId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", outboxEventId);
        payload.put("operationNo", operationNo);
        payload.put("operationType", command.operationType().name());
        payload.put("customerId", command.customerId());
        payload.put("expectedLifecycleVersion", command.expectedLifecycleVersion());
        payload.put("occurredAt", command.requestedAt().toString());
        LifecycleOutboxEntity outbox = new LifecycleOutboxEntity()
                .setEventId(outboxEventId).setAggregateType("ACCOUNT_LIFECYCLE").setAggregateId(operationNo)
                .setEventType(REQUESTED_EVENT).setCausationEventId(auditEventId).setTraceId(command.traceId())
                .setTopic("account.lifecycle.requested.v1").setPartitionKey(Long.toString(command.customerId()))
                .setPayload(json.write(payload)).setStatus("PENDING").setRetryCount(0).setMaxRetryCount(10)
                .setNextRetryAt(now).setCreatedAt(now).setUpdatedAt(now);
        return new LifecycleRuntimeSnapshot(operation, steps, event, outbox);
    }

    private static void validate(CreateLifecycleSnapshotCommand command) {
        if (command == null || command.operationType() == null || command.actorType() == null
                || command.requestedAt() == null) {
            throw new IllegalArgumentException("lifecycle snapshot command is incomplete");
        }
        if (command.requestHash() == null || !command.requestHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
