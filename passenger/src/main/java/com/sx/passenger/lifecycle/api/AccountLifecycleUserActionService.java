package com.sx.passenger.lifecycle.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.LifecycleIdentifierGenerator;
import com.sx.passenger.lifecycle.application.LifecycleOperationConflictException;
import com.sx.passenger.lifecycle.application.UuidLifecycleIdentifierGenerator;
import com.sx.passenger.lifecycle.domain.LifecycleActorType;
import com.sx.passenger.lifecycle.domain.LifecycleOperationStateMachine;
import com.sx.passenger.lifecycle.domain.LifecycleOperationStatus;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleBlockerEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleBlockerMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import com.sx.passenger.model.Customer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 乘客本人可以执行的撤销和解阻重检事务。 */
@Service
public class AccountLifecycleUserActionService {
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final LifecycleBlockerMapper blockers;
    private final LifecycleEventMapper events;
    private final CustomerEntityMapper customers;
    private final LifecycleOperationStateMachine stateMachine = new LifecycleOperationStateMachine();
    private final LifecycleIdentifierGenerator identifiers = new UuidLifecycleIdentifierGenerator();

    public AccountLifecycleUserActionService(
            LifecycleOperationMapper operations,
            LifecycleStepMapper steps,
            LifecycleBlockerMapper blockers,
            LifecycleEventMapper events,
            CustomerEntityMapper customers) {
        this.operations = operations;
        this.steps = steps;
        this.blockers = blockers;
        this.events = events;
        this.customers = customers;
    }

    @Transactional
    public void abort(long customerId, String operationNo) {
        LifecycleOperationEntity operation = requireOwnedForUpdate(customerId, operationNo);
        LifecycleOperationStatus from = LifecycleOperationStatus.valueOf(operation.getStatus());
        stateMachine.requireTransition(
                LifecycleOperationType.ACCOUNT_CANCEL, from, LifecycleOperationStatus.ABORTED,
                Integer.valueOf(1).equals(operation.getIrreversibleStarted()));
        LocalDateTime now = LocalDateTime.now();
        if (customers.abortAccountCancellation(
                customerId, operationNo, operation.getAppliedLifecycleVersion(), now) != 1) {
            throw new LifecycleOperationConflictException("账号注销栅栏已变化，无法撤销");
        }
        Customer customer = customers.selectById(customerId);
        if (customer == null || customer.getLifecycleVersion() == null || customer.getAuthEpoch() == null) {
            throw new IllegalStateException("撤销后账号状态不可用");
        }
        if (operations.abortCancellationCas(
                operation.getId(), operation.getStatus(), operation.getRowVersion(),
                customer.getLifecycleVersion(), customer.getAuthEpoch(), now) != 1) {
            throw new LifecycleOperationConflictException("注销Operation已变化，无法撤销");
        }
        steps.update(null, Wrappers.<LifecycleStepEntity>lambdaUpdate()
                .eq(LifecycleStepEntity::getOperationId, operation.getId())
                .in(LifecycleStepEntity::getStatus,
                        "PENDING", "RUNNING", "BLOCKED", "RETRY_PENDING", "MANUAL_REVIEW")
                .set(LifecycleStepEntity::getStatus, "CANCELLED")
                .set(LifecycleStepEntity::getUpdatedAt, now));
        resolveBlockers(operation.getId(), "USER_ABORTED", now);
        event(operation, operation.getStatus(), "ABORTED", "ACCOUNT_CANCEL_ABORTED", now);
    }

    @Transactional
    public void requestRecheck(long customerId, String operationNo) {
        LifecycleOperationEntity operation = requireOwnedForUpdate(customerId, operationNo);
        stateMachine.requireTransition(
                LifecycleOperationType.ACCOUNT_CANCEL,
                LifecycleOperationStatus.valueOf(operation.getStatus()),
                LifecycleOperationStatus.VALIDATING,
                Integer.valueOf(1).equals(operation.getIrreversibleStarted()));
        LocalDateTime now = LocalDateTime.now();
        int reset = steps.update(null, Wrappers.<LifecycleStepEntity>lambdaUpdate()
                .eq(LifecycleStepEntity::getOperationId, operation.getId())
                .eq(LifecycleStepEntity::getStatus, "BLOCKED")
                .set(LifecycleStepEntity::getStatus, "PENDING")
                .set(LifecycleStepEntity::getCommandEventId, null)
                .set(LifecycleStepEntity::getResultEventId, null)
                .set(LifecycleStepEntity::getResultSnapshot, null)
                .set(LifecycleStepEntity::getLastErrorCode, null)
                .set(LifecycleStepEntity::getLastErrorMessage, null)
                .set(LifecycleStepEntity::getUpdatedAt, now));
        if (reset < 1) {
            throw new LifecycleOperationConflictException("当前没有可重新检查的阻断步骤");
        }
        resolveBlockers(operation.getId(), "USER_RECHECK", now);
        if (operations.recheckBlockedCas(operation.getId(), operation.getRowVersion(), now) != 1) {
            throw new LifecycleOperationConflictException("注销Operation已变化，无法重新检查");
        }
        event(operation, "BLOCKED", "VALIDATING", "ACCOUNT_CANCEL_RECHECK_REQUESTED", now);
    }

    private LifecycleOperationEntity requireOwnedForUpdate(long customerId, String operationNo) {
        LifecycleOperationEntity operation = operations.findByOperationNoForUpdate(operationNo);
        if (operation == null || !Long.valueOf(customerId).equals(operation.getCustomerId())) {
            throw new IllegalArgumentException("生命周期操作不存在");
        }
        if (!"ACCOUNT_CANCEL".equals(operation.getOperationType())) {
            throw new LifecycleOperationConflictException("当前操作不支持该动作");
        }
        return operation;
    }

    private void resolveBlockers(long operationId, String reason, LocalDateTime now) {
        blockers.update(null, Wrappers.<LifecycleBlockerEntity>lambdaUpdate()
                .eq(LifecycleBlockerEntity::getOperationId, operationId)
                .eq(LifecycleBlockerEntity::getStatus, "ACTIVE")
                .set(LifecycleBlockerEntity::getStatus, "RESOLVED")
                .set(LifecycleBlockerEntity::getResolutionReason, reason)
                .set(LifecycleBlockerEntity::getResolvedAt, now)
                .set(LifecycleBlockerEntity::getUpdatedAt, now));
    }

    private void event(
            LifecycleOperationEntity operation,
            String from,
            String to,
            String reason,
            LocalDateTime now) {
        LifecycleEventEntity event = new LifecycleEventEntity()
                .setEventId(identifiers.nextEventId())
                .setOperationId(operation.getId())
                .setCustomerId(operation.getCustomerId())
                .setEventType("LIFECYCLE_OPERATION_STATUS_CHANGED")
                .setFromStatus(from)
                .setToStatus(to)
                .setActorType(LifecycleActorType.CUSTOMER.name())
                .setActorId(Long.toString(operation.getCustomerId()))
                .setReasonCode(reason)
                .setPayloadSnapshot("{}")
                .setCreatedAt(now);
        if (events.insert(event) != 1) {
            throw new IllegalStateException("生命周期用户动作审计事件写入失败");
        }
    }
}
