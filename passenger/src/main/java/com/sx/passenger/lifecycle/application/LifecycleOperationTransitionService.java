package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleOperationStateMachine;
import com.sx.passenger.lifecycle.domain.LifecycleOperationStatus;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class LifecycleOperationTransitionService {
    private final LifecycleOperationMapper operations;
    private final LifecycleEventMapper events;
    private final LifecycleOperationStateMachine stateMachine = new LifecycleOperationStateMachine();
    private final LifecycleIdentifierGenerator identifiers = new UuidLifecycleIdentifierGenerator();

    public LifecycleOperationTransitionService(LifecycleOperationMapper operations, LifecycleEventMapper events) {
        this.operations = operations;
        this.events = events;
    }

    @Transactional
    public void transition(TransitionLifecycleOperationCommand command) {
        LifecycleOperationEntity current = operations.selectById(command.operationId());
        if (current == null) throw new LifecycleOperationConflictException("Lifecycle operation not found");
        LifecycleOperationStatus from = LifecycleOperationStatus.valueOf(current.getStatus());
        stateMachine.requireTransition(LifecycleOperationType.valueOf(current.getOperationType()), from,
                command.targetStatus(), Integer.valueOf(1).equals(current.getIrreversibleStarted()));
        LocalDateTime occurredAt = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        int updated = operations.updateStatusCas(current.getId(), current.getStatus(), command.expectedRowVersion(),
                command.targetStatus().name(), occurredAt);
        if (updated != 1) throw new LifecycleOperationConflictException("Lifecycle operation changed concurrently");
        LifecycleEventEntity event = new LifecycleEventEntity().setEventId(identifiers.nextEventId())
                .setOperationId(current.getId()).setCustomerId(current.getCustomerId())
                .setEventType("LIFECYCLE_OPERATION_STATUS_CHANGED").setFromStatus(from.name())
                .setToStatus(command.targetStatus().name()).setActorType(command.actorType().name())
                .setActorId(command.actorId()).setReasonCode(command.reasonCode()).setTraceId(command.traceId())
                .setPayloadSnapshot(command.sanitizedPayloadJson()).setCreatedAt(occurredAt);
        if (events.insert(event) != 1) throw new IllegalStateException("Failed to insert lifecycle transition event");
    }
}
