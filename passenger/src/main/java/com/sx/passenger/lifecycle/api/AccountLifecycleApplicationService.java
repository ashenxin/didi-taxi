package com.sx.passenger.lifecycle.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.LifecycleOperationConflictException;
import com.sx.passenger.lifecycle.application.cancel.AccountCancellationFenceResult;
import com.sx.passenger.lifecycle.application.cancel.AccountCancellationFenceService;
import com.sx.passenger.lifecycle.application.cancel.FenceAccountCancellationCommand;
import com.sx.passenger.lifecycle.application.phone.ChangeCustomerPhoneCommand;
import com.sx.passenger.lifecycle.application.phone.ChangeCustomerPhoneResult;
import com.sx.passenger.lifecycle.application.phone.CustomerPhoneChangeService;
import com.sx.passenger.lifecycle.orchestration.AccountCancellationOrchestrator;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleBlockerEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleBlockerMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import com.sx.passenger.model.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** passenger 内面向 BFF 的统一账号生命周期应用边界。 */
@Service
public class AccountLifecycleApplicationService {
    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleApplicationService.class);

    private final CustomerEntityMapper customers;
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final LifecycleBlockerMapper blockers;
    private final AccountCancellationFenceService cancellationFence;
    private final AccountCancellationOrchestrator cancellationOrchestrator;
    private final CustomerPhoneChangeService phoneChanges;
    private final AccountLifecycleUserActionService userActions;

    public AccountLifecycleApplicationService(
            CustomerEntityMapper customers,
            LifecycleOperationMapper operations,
            LifecycleStepMapper steps,
            LifecycleBlockerMapper blockers,
            AccountCancellationFenceService cancellationFence,
            AccountCancellationOrchestrator cancellationOrchestrator,
            CustomerPhoneChangeService phoneChanges,
            AccountLifecycleUserActionService userActions) {
        this.customers = customers;
        this.operations = operations;
        this.steps = steps;
        this.blockers = blockers;
        this.cancellationFence = cancellationFence;
        this.cancellationOrchestrator = cancellationOrchestrator;
        this.phoneChanges = phoneChanges;
        this.userActions = userActions;
    }

    /** 建立注销 Operation；编排推进失败不撤销已经成功建立的账号栅栏。 */
    public AccountLifecycleSubmissionView submitCancellation(
            AccountLifecycleSubmitRequest request,
            String idempotencyKey,
            String traceId) {
        requireConfirmed(request);
        Customer customer = requireCustomer(request.customerId());
        long expectedVersion = resolveExpectedVersionForSubmit(customer, request.expectedLifecycleVersion());
        Instant now = Instant.now();
        AccountCancellationFenceResult fenced = cancellationFence.fence(
                new FenceAccountCancellationCommand(
                        customer.getId(), expectedVersion, request.code(), idempotencyKey,
                        Long.toString(customer.getId()), traceId, "{}", now));
        try {
            cancellationOrchestrator.resume(fenced.operationNo());
        } catch (RuntimeException failure) {
            // Operation 已经持久化且账号已建栅栏，必须交给 P6 恢复器继续前向收敛。
            log.error("注销Operation首次推进失败，将由恢复任务继续 operationNo={} errorType={}",
                    fenced.operationNo(), failure.getClass().getSimpleName());
        }
        LifecycleOperationEntity latest = requireOwnedOperation(customer.getId(), fenced.operationNo());
        return new AccountLifecycleSubmissionView(
                fenced.operationNo(), "ACCOUNT_CANCEL", latest.getStatus(), customer.getId(),
                fenced.appliedLifecycleVersion(), fenced.restrictedAuthEpoch(),
                "COMPLETED".equals(latest.getStatus()),
                true, maskPhone(customer.getPhone()));
    }

    /** 同一事务完成换号 Operation、手机号、认证代次、绑定历史、事件和 Outbox。 */
    public AccountLifecycleSubmissionView submitPhoneChange(
            AccountLifecycleSubmitRequest request,
            String idempotencyKey,
            String traceId) {
        Customer customer = requireCustomer(request.customerId());
        long expectedVersion = resolveExpectedVersionForSubmit(customer, request.expectedLifecycleVersion());
        ChangeCustomerPhoneResult changed = phoneChanges.change(new ChangeCustomerPhoneCommand(
                customer.getId(), expectedVersion, requirePhone(request.phone()), request.code(),
                idempotencyKey, Long.toString(customer.getId()), traceId, "{}", Instant.now()));
        return new AccountLifecycleSubmissionView(
                changed.operationNo(), "PHONE_CHANGE", "COMPLETED", customer.getId(),
                changed.appliedLifecycleVersion(), changed.newAuthEpoch(), true,
                changed.requireLogin(),
                maskPhone(request.phone()));
    }

    /** 查询本人 Operation，绝不允许通过 operationNo 越权读取其他乘客进度。 */
    public AccountLifecycleOperationView operation(long customerId, String operationNo) {
        LifecycleOperationEntity operation = requireOwnedOperation(customerId, operationNo);
        List<AccountLifecycleOperationView.StepView> stepViews = steps.selectList(
                        Wrappers.<LifecycleStepEntity>lambdaQuery()
                                .eq(LifecycleStepEntity::getOperationId, operation.getId())
                                .orderByAsc(LifecycleStepEntity::getSequenceNo, LifecycleStepEntity::getId))
                .stream()
                .map(step -> new AccountLifecycleOperationView.StepView(
                        step.getStepCode(), step.getPhase(), step.getStatus(),
                        value(step.getSequenceNo()), value(step.getAttemptCount()), step.getLastErrorCode()))
                .toList();
        List<AccountLifecycleOperationView.BlockerView> blockerViews = blockers.selectList(
                        Wrappers.<LifecycleBlockerEntity>lambdaQuery()
                                .eq(LifecycleBlockerEntity::getOperationId, operation.getId())
                                .orderByAsc(LifecycleBlockerEntity::getId))
                .stream()
                .map(blocker -> new AccountLifecycleOperationView.BlockerView(
                        blocker.getDomainCode(), blocker.getBlockerType(), blocker.getResourceType(),
                        blocker.getResourceId(), blocker.getStatus(), blocker.getResolutionActions()))
                .toList();
        return new AccountLifecycleOperationView(
                operation.getOperationNo(), operation.getOperationType(), operation.getStatus(),
                value(operation.getAppliedLifecycleVersion(), operation.getExpectedLifecycleVersion()),
                Integer.valueOf(1).equals(operation.getIrreversibleStarted()),
                value(operation.getActiveBlockerCount()), operation.getRequestedAt(), operation.getCompletedAt(),
                stepViews, blockerViews);
    }

    public AccountLifecycleOperationView abort(long customerId, String operationNo) {
        userActions.abort(customerId, operationNo);
        return operation(customerId, operationNo);
    }

    public AccountLifecycleOperationView recheck(long customerId, String operationNo) {
        userActions.requestRecheck(customerId, operationNo);
        cancellationOrchestrator.resume(operationNo);
        return operation(customerId, operationNo);
    }

    private LifecycleOperationEntity requireOwnedOperation(long customerId, String operationNo) {
        if (customerId <= 0 || operationNo == null || operationNo.isBlank()) {
            throw new IllegalArgumentException("生命周期操作参数非法");
        }
        LifecycleOperationEntity operation = operations.selectOne(
                Wrappers.<LifecycleOperationEntity>lambdaQuery()
                        .eq(LifecycleOperationEntity::getCustomerId, customerId)
                        .eq(LifecycleOperationEntity::getOperationNo, operationNo)
                        .last("LIMIT 1"));
        if (operation == null) {
            // 统一返回不存在，避免通过 operationNo 探测其他乘客数据。
            throw new IllegalArgumentException("生命周期操作不存在");
        }
        return operation;
    }

    private Customer requireCustomer(Long customerId) {
        if (customerId == null || customerId <= 0) {
            throw new IllegalArgumentException("乘客ID非法");
        }
        Customer customer = customers.selectById(customerId);
        if (customer == null) throw new LifecycleOperationConflictException("账号不存在");
        return customer;
    }

    private static long resolveExpectedVersionForSubmit(Customer customer, Long expectedVersion) {
        // 显式版本原样交给底层应用服务，使相同幂等键在账号状态变化后仍可先命中重放。
        if (expectedVersion != null) {
            if (expectedVersion < 0) throw new IllegalArgumentException("生命周期版本非法");
            return expectedVersion;
        }
        if (!Integer.valueOf(0).equals(customer.getIsDeleted())
                || !"ACTIVE".equals(customer.getLifecycleStatus())) {
            throw new LifecycleOperationConflictException("账号当前不能发起新的生命周期操作");
        }
        Long current = customer.getLifecycleVersion();
        if (current == null || current < 0) {
            throw new IllegalStateException("账号生命周期版本不可用");
        }
        return current;
    }

    private static void requireConfirmed(AccountLifecycleSubmitRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw new IllegalArgumentException("请确认注销风险后再提交");
        }
    }

    private static String requirePhone(String phone) {
        if (phone == null || !phone.matches("1\\d{10}")) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        return phone;
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static long value(Long preferred, Long fallback) {
        Long result = preferred == null ? fallback : preferred;
        return result == null ? 0L : result;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
