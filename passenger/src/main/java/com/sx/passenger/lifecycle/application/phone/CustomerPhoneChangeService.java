package com.sx.passenger.lifecycle.application.phone;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.auth.otp.AtomicOtpService;
import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.auth.otp.OtpSubject;
import com.sx.passenger.auth.metrics.PassengerAuthMetrics;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.CreateLifecycleSnapshotCommand;
import com.sx.passenger.lifecycle.application.LifecycleIdentifierGenerator;
import com.sx.passenger.lifecycle.application.LifecycleJson;
import com.sx.passenger.lifecycle.application.LifecycleOperationConflictException;
import com.sx.passenger.lifecycle.application.LifecycleRequestHasher;
import com.sx.passenger.lifecycle.application.LifecycleRuntimeSnapshot;
import com.sx.passenger.lifecycle.application.LifecycleRuntimeSnapshotFactory;
import com.sx.passenger.lifecycle.application.LifecycleSnapshotStore;
import com.sx.passenger.lifecycle.application.UuidLifecycleIdentifierGenerator;
import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationStateMachine;
import com.sx.passenger.lifecycle.domain.LifecycleOperationStatus;
import com.sx.passenger.lifecycle.domain.LifecycleStepStateMachine;
import com.sx.passenger.lifecycle.domain.LifecycleStepStatus;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.CustomerPhoneBindingHistoryMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import com.sx.passenger.lifecycle.plan.LifecyclePlanRegistry;
import com.sx.passenger.model.Customer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CustomerPhoneChangeService {
    private final AtomicOtpService otp;
    private final LifecycleSnapshotStore snapshots;
    private final CustomerEntityMapper customers;
    private final CustomerPhoneBindingHistoryMapper bindings;
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final LifecycleEventMapper events;
    private final LifecycleOutboxMapper outboxes;
    private final PhoneBindingValueFactory bindingValues;
    private final LifecycleRequestHasher hasher;
    private final LifecycleRuntimeSnapshotFactory snapshotFactory;
    private final LifecycleIdentifierGenerator identifiers = new UuidLifecycleIdentifierGenerator();
    private final LifecycleJson json = new LifecycleJson();
    private final LifecycleOperationStateMachine operationStateMachine = new LifecycleOperationStateMachine();
    private final LifecycleStepStateMachine stepStateMachine = new LifecycleStepStateMachine();
    private final TransactionTemplate withoutTransaction;
    private final TransactionTemplate databaseTransaction;
    private final PassengerAuthMetrics metrics;

    public CustomerPhoneChangeService(AtomicOtpService otp,
                                      LifecycleSnapshotStore snapshots,
                                      CustomerEntityMapper customers,
                                      CustomerPhoneBindingHistoryMapper bindings,
                                      LifecycleOperationMapper operations,
                                      LifecycleStepMapper steps,
                                      LifecycleEventMapper events,
                                      LifecycleOutboxMapper outboxes,
                                      PhoneBindingValueFactory bindingValues,
                                      LifecycleRequestHasher hasher,
                                      LifecyclePlanRegistry plans,
                                      PlatformTransactionManager transactionManager,
                                      PassengerAuthMetrics metrics) {
        this.otp = otp;
        this.snapshots = snapshots;
        this.customers = customers;
        this.bindings = bindings;
        this.operations = operations;
        this.steps = steps;
        this.events = events;
        this.outboxes = outboxes;
        this.bindingValues = bindingValues;
        this.hasher = hasher;
        this.snapshotFactory = new LifecycleRuntimeSnapshotFactory(
                plans, new UuidLifecycleIdentifierGenerator(), new LifecycleJson());
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.databaseTransaction = new TransactionTemplate(transactionManager);
        this.databaseTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.metrics = metrics;
    }

    public ChangeCustomerPhoneResult change(ChangeCustomerPhoneCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ChangeCustomerPhoneResult result = withoutTransaction.execute(status -> changeOutsideTransaction(command));
        return Objects.requireNonNull(result, "phone change execution returned no result");
    }

    private ChangeCustomerPhoneResult changeOutsideTransaction(ChangeCustomerPhoneCommand command) {
        String requestHash = hasher.hash(command);
        var prior = snapshots.findByIdempotency(
                command.customerId(), LifecycleOperationType.PHONE_CHANGE, command.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestHash);

        Customer current = customers.selectById(command.customerId());
        requireChangeAllowed(current, command);
        Customer occupied = customers.selectOne(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getPhone, command.newPhone()).eq(Customer::getIsDeleted, 0).last("LIMIT 1"));
        if (occupied != null && !occupied.getId().equals(command.customerId())) {
            throw new LifecycleOperationConflictException("New phone is already occupied");
        }

        OtpConsumeResult consumed = otp.consume(OtpPurpose.PHONE_CHANGE_NEW_PHONE,
                OtpSubject.phoneChange(command.customerId(), command.newPhone(),
                        command.expectedLifecycleVersion()), command.otpCode());
        if (consumed != OtpConsumeResult.CONSUMED) {
            throw new IllegalArgumentException("OTP is invalid or expired");
        }

        ChangeCustomerPhoneResult result = databaseTransaction.execute(
                status -> applyChange(command, requestHash));
        return Objects.requireNonNull(result, "phone change transaction returned no result");
    }

    private ChangeCustomerPhoneResult applyChange(ChangeCustomerPhoneCommand command, String requestHash) {
        LifecycleRuntimeSnapshot snapshot = snapshotFactory.create(new CreateLifecycleSnapshotCommand(
                command.customerId(), LifecycleOperationType.PHONE_CHANGE, command.idempotencyKey(), requestHash,
                command.expectedLifecycleVersion(), LifecycleActorType.CUSTOMER, command.actorId(), command.traceId(),
                command.sanitizedRequestContextJson(), command.requestedAt()));
        LocalDateTime now = LocalDateTime.ofInstant(command.requestedAt(), ZoneOffset.UTC);
        LifecycleOperationEntity operation = snapshot.operation();

        int customerUpdated;
        try {
            customerUpdated = customers.changePhoneCas(command.customerId(), command.newPhone(),
                    command.expectedLifecycleVersion());
        } catch (DuplicateKeyException ex) {
            if (hasConstraint(ex, "uk_customer_phone_active")) {
                metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);
                throw new LifecycleOperationConflictException("New phone is already occupied");
            }
            throw ex;
        }
        if (customerUpdated != 1) {
            metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);
            metrics.epochBump(PassengerAuthMetrics.EpochCause.PHONE_CHANGE,
                    PassengerAuthMetrics.OperationResult.CONFLICT);
            throw new LifecycleOperationConflictException("Customer lifecycle changed concurrently");
        }
        Customer changed = customers.selectById(command.customerId());
        if (changed == null) throw new LifecycleOperationConflictException("Customer disappeared during phone change");

        if (bindings.replaceActive(command.customerId(), operation.getOperationNo(), now) != 1) {
            metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);
            throw new LifecycleOperationConflictException("Active phone binding changed concurrently");
        }
        Long maxVersion = bindings.selectMaxBindingVersion(command.customerId());
        long nextVersion = maxVersion == null ? 1L : maxVersion + 1L;
        if (bindings.insert(bindingValues.active(command.customerId(), nextVersion, command.newPhone(),
                operation.getOperationNo(), now)) != 1) {
            throw new IllegalStateException("Failed to insert active phone binding");
        }

        snapshots.persistNew(snapshot);
        operationStateMachine.requireTransition(LifecycleOperationType.PHONE_CHANGE,
                LifecycleOperationStatus.REQUESTED, LifecycleOperationStatus.EXECUTING, false);
        if (operations.startPhoneChangeCas(operation.getId(), 0L, now) != 1) {
            metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);
            throw new LifecycleOperationConflictException("Phone change operation could not start");
        }
        insertTransitionEvent(operation, "REQUESTED", "EXECUTING", "PHONE_CHANGE_STARTED", command, now);

        stepStateMachine.requireTransition(LifecycleStepStatus.PENDING, LifecycleStepStatus.RUNNING);
        int startedSteps = steps.update(null, Wrappers.<LifecycleStepEntity>lambdaUpdate()
                .eq(LifecycleStepEntity::getOperationId, operation.getId())
                .eq(LifecycleStepEntity::getStatus, "PENDING")
                .set(LifecycleStepEntity::getStatus, "RUNNING")
                .set(LifecycleStepEntity::getAttemptCount, 1)
                .set(LifecycleStepEntity::getStartedAt, now)
                .set(LifecycleStepEntity::getUpdatedAt, now));
        if (startedSteps != snapshot.steps().size()) {
            metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);
            throw new LifecycleOperationConflictException("Phone change steps could not start");
        }
        stepStateMachine.requireTransition(LifecycleStepStatus.RUNNING, LifecycleStepStatus.SUCCEEDED);
        int completedSteps = steps.update(null, Wrappers.<LifecycleStepEntity>lambdaUpdate()
                .eq(LifecycleStepEntity::getOperationId, operation.getId())
                .eq(LifecycleStepEntity::getStatus, "RUNNING")
                .set(LifecycleStepEntity::getStatus, "SUCCEEDED")
                .set(LifecycleStepEntity::getCompletedAt, now)
                .set(LifecycleStepEntity::getUpdatedAt, now));
        if (completedSteps != snapshot.steps().size()) {
            metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);
            throw new LifecycleOperationConflictException("Phone change steps changed concurrently");
        }

        operationStateMachine.requireTransition(LifecycleOperationType.PHONE_CHANGE,
                LifecycleOperationStatus.EXECUTING, LifecycleOperationStatus.COMPLETED, false);
        if (operations.completePhoneChangeCas(operation.getId(), 1L, changed.getLifecycleVersion(),
                changed.getAuthEpoch(), now) != 1) {
            metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);
            throw new LifecycleOperationConflictException("Phone change operation could not complete");
        }
        String completedEventId = insertTransitionEvent(operation, "EXECUTING", "COMPLETED",
                "PHONE_CHANGE_COMPLETED", command, now);
        insertCompletedOutbox(operation, changed, completedEventId, command, now);

        metrics.epochBump(PassengerAuthMetrics.EpochCause.PHONE_CHANGE,
                PassengerAuthMetrics.OperationResult.SUCCESS);

        return new ChangeCustomerPhoneResult(operation.getId(), operation.getOperationNo(), command.customerId(),
                changed.getLifecycleVersion(), changed.getAuthEpoch(), true);
    }

    private String insertTransitionEvent(LifecycleOperationEntity operation, String from, String to, String reason,
                                         ChangeCustomerPhoneCommand command, LocalDateTime now) {
        String eventId = identifiers.nextEventId();
        LifecycleEventEntity event = new LifecycleEventEntity().setEventId(eventId)
                .setOperationId(operation.getId()).setCustomerId(command.customerId())
                .setEventType("LIFECYCLE_OPERATION_STATUS_CHANGED").setFromStatus(from).setToStatus(to)
                .setActorType(LifecycleActorType.CUSTOMER.name()).setActorId(command.actorId())
                .setReasonCode(reason).setTraceId(command.traceId()).setPayloadSnapshot("{}").setCreatedAt(now);
        if (events.insert(event) != 1) throw new IllegalStateException("Failed to insert phone change event");
        return eventId;
    }

    private void insertCompletedOutbox(LifecycleOperationEntity operation, Customer changed, String causationEventId,
                                       ChangeCustomerPhoneCommand command, LocalDateTime now) {
        String eventId = identifiers.nextEventId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("operationNo", operation.getOperationNo());
        payload.put("operationType", LifecycleOperationType.PHONE_CHANGE.name());
        payload.put("customerId", command.customerId());
        payload.put("appliedLifecycleVersion", changed.getLifecycleVersion());
        payload.put("authEpoch", changed.getAuthEpoch());
        payload.put("occurredAt", command.requestedAt().toString());
        LifecycleOutboxEntity outbox = new LifecycleOutboxEntity().setEventId(eventId)
                .setOperationId(operation.getId()).setAggregateType("ACCOUNT_LIFECYCLE")
                .setAggregateId(operation.getOperationNo()).setEventType("LIFECYCLE_PHONE_CHANGED")
                .setCausationEventId(causationEventId).setTraceId(command.traceId())
                .setTopic("account.lifecycle.phone-changed.v1").setPartitionKey(Long.toString(command.customerId()))
                .setPayload(json.write(payload)).setStatus("PENDING").setRetryCount(0).setMaxRetryCount(10)
                .setNextRetryAt(now).setCreatedAt(now).setUpdatedAt(now);
        if (outboxes.insert(outbox) != 1) throw new IllegalStateException("Failed to insert phone changed outbox");
    }

    private static void requireChangeAllowed(Customer current, ChangeCustomerPhoneCommand command) {
        if (current == null || !Integer.valueOf(0).equals(current.getIsDeleted())
                || !"ACTIVE".equals(current.getLifecycleStatus())) {
            throw new LifecycleOperationConflictException("Customer is not active");
        }
        if (command.newPhone().equals(current.getPhone())) {
            throw new IllegalArgumentException("New phone must not be the same as current phone");
        }
        if (!Long.valueOf(command.expectedLifecycleVersion()).equals(current.getLifecycleVersion())) {
            throw new LifecycleOperationConflictException("Customer lifecycle version changed");
        }
        if (current.getCurrentLifecycleOperationNo() != null) {
            throw new LifecycleOperationConflictException("Customer already has an active lifecycle operation");
        }
    }

    private static ChangeCustomerPhoneResult replay(LifecycleOperationEntity operation, String requestHash) {
        if (!requestHash.equals(operation.getRequestHash())) {
            throw new LifecycleOperationConflictException("Idempotency key was used for another request");
        }
        if (!"COMPLETED".equals(operation.getStatus()) || operation.getAppliedLifecycleVersion() == null
                || operation.getRestrictedAuthEpoch() == null) {
            throw new LifecycleOperationConflictException("Existing phone change operation is not completed");
        }
        return new ChangeCustomerPhoneResult(operation.getId(), operation.getOperationNo(), operation.getCustomerId(),
                operation.getAppliedLifecycleVersion(), operation.getRestrictedAuthEpoch(), true);
    }

    private static boolean hasConstraint(Throwable failure, String expectedConstraint) {
        Pattern expectedToken = Pattern.compile("(?i)(?<![a-z0-9_])"
                + Pattern.quote(expectedConstraint) + "(?![a-z0-9_])");
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (containsConstraintToken(current.getMessage(), expectedToken)
                    || containsConstraintToken(readConstraintName(current), expectedToken)) {
                return true;
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
            if (current instanceof SQLException sql && sql.getNextException() != null) {
                pending.addLast(sql.getNextException());
            }
        }
        return false;
    }

    private static String readConstraintName(Throwable failure) {
        try {
            Method accessor = failure.getClass().getMethod("getConstraintName");
            if (accessor.getParameterCount() == 0 && accessor.getReturnType() == String.class) {
                return (String) accessor.invoke(failure);
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Most JDBC exceptions expose the constraint only in their message/cause.
        }
        return null;
    }

    private static boolean containsConstraintToken(String value, Pattern expectedToken) {
        return value != null && expectedToken.matcher(value).find();
    }
}
