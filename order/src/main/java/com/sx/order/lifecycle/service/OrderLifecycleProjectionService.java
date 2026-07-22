package com.sx.order.lifecycle.service;

import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.exception.AccountLifecycleUnknownException;
import com.sx.order.lifecycle.exception.OrderLifecycleProjectionConflictException;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.model.OrderAccountLifecycleProjection;
import com.sx.order.lifecycle.model.OrderLifecycleStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class OrderLifecycleProjectionService {
    private final OrderAccountLifecycleProjectionMapper mapper;

    public OrderLifecycleProjectionService(OrderAccountLifecycleProjectionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public ProjectionApplyResult apply(ApplyOrderLifecycleProjectionCommand command) {
        return applyUnderLock(command);
    }

    public ProjectionApplyResult applyUnderLock(ApplyOrderLifecycleProjectionCommand command) {
        OrderLifecycleStatus incomingStatus = parseStatus(command.lifecycleStatus());
        OrderAccountLifecycleProjection current = mapper.selectForUpdate(command.customerId());
        if (current == null) {
            if (incomingStatus != OrderLifecycleStatus.ACTIVE || hasText(command.operationNo())) {
                throw new AccountLifecycleUnknownException("账户生命周期投影不存在，不能从非ACTIVE状态开始");
            }
            mapper.insert(toProjection(command));
            return ProjectionApplyResult.APPLIED;
        }

        if (sameEvent(current, command)) {
            return ProjectionApplyResult.REPLAYED;
        }
        if (OrderLifecycleStatus.CANCELLED.name().equals(current.getLifecycleStatus())) {
            throw conflict("已注销投影是终态，不能继续变更");
        }
        if (command.lifecycleVersion() < current.getLifecycleVersion()) {
            throw conflict("收到过期的账户生命周期版本");
        }
        if (command.lifecycleVersion() == current.getLifecycleVersion()) {
            throw conflict("同一账户生命周期版本的内容不一致");
        }

        OrderAccountLifecycleProjection next = toProjection(command);
        if (mapper.updateWithVersion(next, current.getRowVersion()) != 1) {
            throw conflict("账户生命周期投影并发更新冲突");
        }
        return ProjectionApplyResult.APPLIED;
    }

    private static OrderAccountLifecycleProjection toProjection(
            ApplyOrderLifecycleProjectionCommand command) {
        return new OrderAccountLifecycleProjection()
                .setCustomerId(command.customerId())
                .setBusinessStatus(command.businessStatus())
                .setLifecycleStatus(parseStatus(command.lifecycleStatus()).name())
                .setLifecycleVersion(command.lifecycleVersion())
                .setOperationNo(blankToNull(command.operationNo()))
                .setSourceEventId(command.sourceEventId().trim())
                .setRowVersion(0L)
                .setUpdatedAt(command.updatedAt());
    }

    private static boolean sameEvent(OrderAccountLifecycleProjection current,
                                     ApplyOrderLifecycleProjectionCommand command) {
        return current.getLifecycleVersion() == command.lifecycleVersion()
                && current.getBusinessStatus() == command.businessStatus()
                && Objects.equals(current.getLifecycleStatus(), parseStatus(command.lifecycleStatus()).name())
                && Objects.equals(current.getOperationNo(), blankToNull(command.operationNo()))
                && Objects.equals(current.getSourceEventId(), command.sourceEventId().trim());
    }

    private static OrderLifecycleStatus parseStatus(String value) {
        try {
            return OrderLifecycleStatus.valueOf(value.trim());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("未知的账户生命周期状态: " + value, ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static OrderLifecycleProjectionConflictException conflict(String message) {
        return new OrderLifecycleProjectionConflictException(message);
    }
}
