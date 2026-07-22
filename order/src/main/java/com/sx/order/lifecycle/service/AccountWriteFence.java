package com.sx.order.lifecycle.service;

import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.exception.AccountLifecycleBlockedException;
import com.sx.order.lifecycle.exception.AccountLifecycleUnknownException;
import com.sx.order.lifecycle.model.OrderAccountLifecycleProjection;
import com.sx.order.lifecycle.model.OrderLifecycleStatus;
import com.sx.order.lifecycle.model.OrderWriteAction;
import org.springframework.stereotype.Service;

@Service
public class AccountWriteFence {
    private final OrderAccountLifecycleProjectionMapper mapper;

    public AccountWriteFence(OrderAccountLifecycleProjectionMapper mapper) {
        this.mapper = mapper;
    }

    public void lockAndRequireAllowed(long customerId, OrderWriteAction action) {
        OrderAccountLifecycleProjection projection = mapper.selectForUpdate(customerId);
        if (projection == null) {
            throw new AccountLifecycleUnknownException("账户生命周期投影缺失，暂不能执行" + action);
        }
        final OrderLifecycleStatus status;
        try {
            status = OrderLifecycleStatus.valueOf(projection.getLifecycleStatus());
        } catch (RuntimeException ex) {
            throw new AccountLifecycleUnknownException("账户生命周期投影状态未知");
        }
        if (projection.getBusinessStatus() == null || projection.getBusinessStatus() != 0
                || status != OrderLifecycleStatus.ACTIVE) {
            throw new AccountLifecycleBlockedException("账户当前状态不允许执行" + action);
        }
    }
}
