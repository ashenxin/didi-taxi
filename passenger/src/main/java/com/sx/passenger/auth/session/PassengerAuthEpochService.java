package com.sx.passenger.auth.session;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.app.dto.AppAuthCustomerBrief;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.model.Customer;
import com.sx.passenger.auth.metrics.PassengerAuthMetrics;
import com.sx.passenger.time.PassengerPersistenceTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class PassengerAuthEpochService {

    private static final String ACTIVE = "ACTIVE";
    private static final String CANCELLING = "CANCELLING";
    private static final Set<String> ACTIVE_OPERATION_STATUSES = Set.of(
            "REQUESTED", "FENCED", "VALIDATING", "BLOCKED", "EXECUTING", "RETRY_PENDING", "MANUAL_REVIEW");

    private final CustomerEntityMapper customers;
    private final LifecycleOperationMapper operations;
    private final PassengerAuthMetrics metrics;

    public PassengerAuthEpochService(CustomerEntityMapper customers, LifecycleOperationMapper operations,
                                     PassengerAuthMetrics metrics) {
        this.customers = customers;
        this.operations = operations;
        this.metrics = metrics;
    }

    @Transactional
    public AppAuthCustomerBrief completeAuthentication(long customerId) {
        requirePositiveCustomerId(customerId);
        if (customers.bumpAuthEpochForAuthentication(customerId) != 1) {
            metrics.epochBump(PassengerAuthMetrics.EpochCause.AUTHENTICATION,
                    PassengerAuthMetrics.OperationResult.REJECTED);
            throw new AuthStateRejectedException();
        }
        metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.AUTHENTICATION);
        Customer current = customers.selectById(customerId);
        AuthSessionScope scope = scopeOf(current);
        if (scope == AuthSessionScope.LIFECYCLE_RESTRICTED
                && operations.updateRestrictedAuthEpoch(customerId, current.getCurrentLifecycleOperationNo(),
                current.getAuthEpoch(), PassengerPersistenceTime.now()) != 1) {
            throw new AuthStateRejectedException();
        }
        return AppAuthCustomerBrief.from(current, scope.name());
    }

    @Transactional
    public long logout(long customerId, long expectedAuthEpoch) {
        requirePositiveCustomerId(customerId);
        if (expectedAuthEpoch < 0 || customers.bumpAuthEpochForLogout(customerId, expectedAuthEpoch) != 1) {
            metrics.epochBump(PassengerAuthMetrics.EpochCause.LOGOUT,
                    PassengerAuthMetrics.OperationResult.CONFLICT);
            throw new AuthEpochConflictException();
        }
        metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.LOGOUT);
        Customer current = customers.selectById(customerId);
        if (current == null || current.getAuthEpoch() == null) {
            throw new AuthEpochConflictException();
        }
        return current.getAuthEpoch();
    }

    @Transactional(readOnly = true)
    public AuthoritativeAuthState loadState(long customerId) {
        requirePositiveCustomerId(customerId);
        Customer current = customers.selectById(customerId);
        if (current == null) {
            return new AuthoritativeAuthState(customerId, null, null, 0L, null, null, false);
        }
        long authEpoch = current.getAuthEpoch() == null ? 0L : current.getAuthEpoch();
        if (current.getIsDeleted() == null || current.getIsDeleted() != 0
                || current.getStatus() == null || current.getStatus() != 0) {
            return rejectedState(current, authEpoch);
        }
        AuthSessionScope scope;
        try {
            scope = scopeOf(current);
        } catch (AuthStateRejectedException ignored) {
            return rejectedState(current, authEpoch);
        }
        if (scope == AuthSessionScope.LIFECYCLE_RESTRICTED && !hasActiveBoundOperation(current)) {
            return rejectedState(current, authEpoch);
        }
        return new AuthoritativeAuthState(current.getId(), current.getStatus(), current.getLifecycleStatus(),
                authEpoch, operationNo(current, scope), scope, true);
    }

    private boolean hasActiveBoundOperation(Customer current) {
        LifecycleOperationEntity operation = operations.selectOne(
                Wrappers.<LifecycleOperationEntity>lambdaQuery()
                        .eq(LifecycleOperationEntity::getOperationNo, current.getCurrentLifecycleOperationNo())
                        .eq(LifecycleOperationEntity::getCustomerId, current.getId())
                        .last("LIMIT 1"));
        return operation != null && ACTIVE_OPERATION_STATUSES.contains(operation.getStatus());
    }

    private static AuthoritativeAuthState rejectedState(Customer current, long authEpoch) {
        return new AuthoritativeAuthState(current.getId(), current.getStatus(), current.getLifecycleStatus(),
                authEpoch, current.getCurrentLifecycleOperationNo(), null, false);
    }

    private static AuthSessionScope scopeOf(Customer customer) {
        if (customer == null || customer.getAuthEpoch() == null || customer.getAuthEpoch() < 0) {
            throw new AuthStateRejectedException();
        }
        if (ACTIVE.equals(customer.getLifecycleStatus())) {
            return AuthSessionScope.NORMAL;
        }
        if (CANCELLING.equals(customer.getLifecycleStatus())
                && customer.getCurrentLifecycleOperationNo() != null
                && !customer.getCurrentLifecycleOperationNo().isBlank()) {
            return AuthSessionScope.LIFECYCLE_RESTRICTED;
        }
        throw new AuthStateRejectedException();
    }

    private static String operationNo(Customer customer, AuthSessionScope scope) {
        return scope == AuthSessionScope.LIFECYCLE_RESTRICTED
                ? customer.getCurrentLifecycleOperationNo()
                : null;
    }

    private static void requirePositiveCustomerId(long customerId) {
        if (customerId <= 0) {
            throw new IllegalArgumentException("customerId must be positive");
        }
    }
}
