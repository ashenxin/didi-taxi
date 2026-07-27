package com.sx.passenger.lifecycle.application.cancel;

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
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.plan.LifecyclePlanRegistry;
import com.sx.passenger.model.Customer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 注销流程的建栅栏应用服务。
 *
 * <p>先在数据库事务之外完成幂等检查和 OTP 原子消费，再在单一数据库事务中：
 * 更新 customer 为 CANCELLING、递增认证/生命周期版本、保存运行时快照并把 Operation
 * 迁移到 FENCED。OTP 一旦消费不会因后续数据库失败而恢复。
 */
@Service
public class AccountCancellationFenceService {
    private final AtomicOtpService otp;
    private final LifecycleSnapshotStore snapshots;
    private final CustomerEntityMapper customers;
    private final LifecycleOperationMapper operations;
    private final LifecycleEventMapper events;
    private final TransactionTemplate withoutTransaction;
    private final TransactionTemplate databaseTransaction;
    private final LifecycleRequestHasher hasher;
    private final LifecycleRuntimeSnapshotFactory snapshotFactory;
    private final LifecycleIdentifierGenerator identifiers = new UuidLifecycleIdentifierGenerator();
    private final PassengerAuthMetrics metrics;

    public AccountCancellationFenceService(AtomicOtpService otp,
                                            LifecycleSnapshotStore snapshots,
                                            CustomerEntityMapper customers,
                                            LifecycleOperationMapper operations,
                                            LifecycleEventMapper events,
                                            PlatformTransactionManager transactionManager,
                                            LifecycleRequestHasher hasher,
                                            LifecyclePlanRegistry plans,
                                            PassengerAuthMetrics metrics) {
        this.otp = otp;
        this.snapshots = snapshots;
        this.customers = customers;
        this.operations = operations;
        this.events = events;
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.databaseTransaction = new TransactionTemplate(transactionManager);
        this.databaseTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.hasher = hasher;
        this.snapshotFactory = new LifecycleRuntimeSnapshotFactory(
                plans, new UuidLifecycleIdentifierGenerator(), new LifecycleJson());
        this.metrics = metrics;
    }

    /** 建立注销栅栏；同一幂等键和相同请求可安全返回既有结果。 */
    public AccountCancellationFenceResult fence(FenceAccountCancellationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AccountCancellationFenceResult result = withoutTransaction.execute(
                status -> fenceOutsideTransaction(command));
        return Objects.requireNonNull(result, "cancellation fence execution returned no result");
    }

    /** 刻意在事务外消费 OTP，避免数据库回滚造成验证码“复活”。 */
    private AccountCancellationFenceResult fenceOutsideTransaction(FenceAccountCancellationCommand command) {
        String requestHash = hasher.hash(command);
        var prior = snapshots.findByIdempotency(
                command.customerId(), LifecycleOperationType.ACCOUNT_CANCEL, command.idempotencyKey());
        if (prior.isPresent()) {
            return replay(prior.get(), requestHash);
        }

        OtpConsumeResult consumed = otp.consume(OtpPurpose.ACCOUNT_CANCEL,
                OtpSubject.accountCancel(command.customerId(), command.expectedLifecycleVersion()),
                command.otpCode());
        if (consumed != OtpConsumeResult.CONSUMED) {
            throw new IllegalArgumentException("OTP is invalid or expired");
        }

        AccountCancellationFenceResult result = databaseTransaction.execute(
                status -> createFence(command, requestHash));
        return Objects.requireNonNull(result, "cancellation fence transaction returned no result");
    }

    /** 在数据库事务内原子写入账号栅栏、快照和 FENCED 审计事件。 */
    private AccountCancellationFenceResult createFence(FenceAccountCancellationCommand command,
                                                        String requestHash) {
        LifecycleRuntimeSnapshot snapshot = snapshotFactory.create(new CreateLifecycleSnapshotCommand(
                command.customerId(), LifecycleOperationType.ACCOUNT_CANCEL, command.idempotencyKey(), requestHash,
                command.expectedLifecycleVersion(), LifecycleActorType.CUSTOMER, command.actorId(), command.traceId(),
                command.sanitizedRequestContextJson(), command.requestedAt()));
        LocalDateTime now = LocalDateTime.ofInstant(command.requestedAt(), ZoneOffset.UTC);
        String operationNo = snapshot.operation().getOperationNo();
        int customerUpdated = customers.fenceAccountCancellation(
                command.customerId(), command.expectedLifecycleVersion(), operationNo, now);
        if (customerUpdated != 1) {
            metrics.lifecycleCasConflict(LifecycleOperationType.ACCOUNT_CANCEL);
            metrics.epochBump(PassengerAuthMetrics.EpochCause.ACCOUNT_CANCEL,
                    PassengerAuthMetrics.OperationResult.CONFLICT);
            throw new LifecycleOperationConflictException("Customer lifecycle changed concurrently");
        }
        metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.ACCOUNT_CANCEL);
        Customer fencedCustomer = customers.selectById(command.customerId());
        if (fencedCustomer == null) {
            throw new LifecycleOperationConflictException("Customer disappeared while creating cancellation fence");
        }

        snapshots.persistNew(snapshot);
        LifecycleOperationEntity operation = snapshot.operation();
        int operationUpdated = operations.fenceRequestedCas(operation.getId(), 0L,
                fencedCustomer.getAuthEpoch(), fencedCustomer.getLifecycleVersion(), now);
        if (operationUpdated != 1) {
            metrics.lifecycleCasConflict(LifecycleOperationType.ACCOUNT_CANCEL);
            throw new LifecycleOperationConflictException("Lifecycle operation changed while creating fence");
        }
        LifecycleEventEntity fencedEvent = new LifecycleEventEntity()
                .setEventId(identifiers.nextEventId()).setOperationId(operation.getId())
                .setCustomerId(command.customerId()).setEventType("LIFECYCLE_OPERATION_STATUS_CHANGED")
                .setFromStatus("REQUESTED").setToStatus("FENCED")
                .setActorType(LifecycleActorType.CUSTOMER.name()).setActorId(command.actorId())
                .setReasonCode("ACCOUNT_CANCEL_FENCED").setTraceId(command.traceId())
                .setPayloadSnapshot("{}").setCreatedAt(now);
        if (events.insert(fencedEvent) != 1) {
            throw new IllegalStateException("Failed to insert lifecycle fence event");
        }
        return new AccountCancellationFenceResult(operation.getId(), operationNo, command.customerId(),
                fencedCustomer.getLifecycleVersion(), fencedCustomer.getAuthEpoch(), "FENCED");
    }

    /** 校验幂等键对应内容一致且既有操作已完成建栅栏。 */
    private static AccountCancellationFenceResult replay(LifecycleOperationEntity operation, String requestHash) {
        if (!requestHash.equals(operation.getRequestHash())) {
            throw new LifecycleOperationConflictException("Idempotency key was used for another request");
        }
        if (operation.getAppliedLifecycleVersion() == null || operation.getRestrictedAuthEpoch() == null) {
            throw new LifecycleOperationConflictException("Existing lifecycle operation is not fenced");
        }
        return new AccountCancellationFenceResult(operation.getId(), operation.getOperationNo(),
                operation.getCustomerId(), operation.getAppliedLifecycleVersion(),
                operation.getRestrictedAuthEpoch(), operation.getStatus());
    }
}
