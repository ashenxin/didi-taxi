package com.sx.calculate.lifecycle.service;

import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleProjectionMapper;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleBlockedException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleUnknownException;
import com.sx.calculate.lifecycle.model.CalculateAccountLifecycleProjection;
import com.sx.calculate.lifecycle.model.CalculateLifecycleStatus;
import com.sx.calculate.lifecycle.model.CalculateWriteAction;
import com.sx.calculate.lifecycle.metrics.CalculateLifecycleMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@Slf4j
public class CalculateAccountWriteFence {
    private final CalculateAccountLifecycleProjectionMapper projections;
    private final CalculateLifecycleWriteFenceProperties properties;
    private final CalculateLifecycleMetrics metrics;

    public CalculateAccountWriteFence(CalculateAccountLifecycleProjectionMapper projections,
                                      CalculateLifecycleWriteFenceProperties properties,
                                      CalculateLifecycleMetrics metrics) {
        this.projections = projections;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void lockAndRequireActive(long customerId, CalculateWriteAction action) {
        Mode mode = mode();
        if (mode == Mode.OFF) {
            metrics.writeFence(action.name(), CalculateLifecycleMetrics.WriteFenceDecision.ALLOW);
            return;
        }
        CalculateAccountLifecycleProjection projection = projections.selectForUpdate(customerId);
        RuntimeException rejection = requireActive(projection, action);
        if (rejection == null) {
            metrics.writeFence(action.name(), CalculateLifecycleMetrics.WriteFenceDecision.ALLOW);
            return;
        }
        if (mode == Mode.SHADOW) {
            log.warn("calculate lifecycle write fence shadow rejection customerId={} action={} reason={}",
                    customerId, action, rejection.getClass().getSimpleName());
            metrics.writeFence(action.name(), CalculateLifecycleMetrics.WriteFenceDecision.UNKNOWN);
            return;
        }
        metrics.writeFence(action.name(), rejection instanceof CalculateLifecycleBlockedException
                ? CalculateLifecycleMetrics.WriteFenceDecision.BLOCKED
                : CalculateLifecycleMetrics.WriteFenceDecision.UNKNOWN);
        throw rejection;
    }

    public void lockAndRequireResolvable(long customerId, CalculateWriteAction action) {
        Mode mode = mode();
        if (mode == Mode.OFF) {
            metrics.writeFence(action.name(), CalculateLifecycleMetrics.WriteFenceDecision.ALLOW);
            return;
        }
        CalculateAccountLifecycleProjection projection = projections.selectForUpdate(customerId);
        RuntimeException rejection = requireKnownProjection(projection, action);
        if (rejection == null) {
            metrics.writeFence(action.name(), CalculateLifecycleMetrics.WriteFenceDecision.ALLOW);
            return;
        }
        if (mode == Mode.SHADOW) {
            log.warn("calculate lifecycle resolution fence shadow rejection customerId={} action={} reason={}",
                    customerId, action, rejection.getClass().getSimpleName());
            metrics.writeFence(action.name(), CalculateLifecycleMetrics.WriteFenceDecision.UNKNOWN);
            return;
        }
        metrics.writeFence(action.name(), CalculateLifecycleMetrics.WriteFenceDecision.UNKNOWN);
        throw rejection;
    }

    public void lockAndRequireCurrentCancellation(long customerId, String operationNo,
                                                  String stepCode) {
        if (operationNo == null || operationNo.isBlank()) {
            throw new IllegalArgumentException("operationNo不能为空");
        }
        if (stepCode == null || stepCode.isBlank()) {
            throw new IllegalArgumentException("stepCode不能为空");
        }
        CalculateAccountLifecycleProjection projection = projections.selectForUpdate(customerId);
        if (projection == null) {
            throw new CalculateLifecycleUnknownException("Calculate生命周期投影缺失");
        }
        CalculateLifecycleStatus status = status(projection);
        if (status != CalculateLifecycleStatus.CANCELLING
                || !Objects.equals(operationNo.trim(), normalize(projection.getOperationNo()))) {
            throw new CalculateLifecycleBlockedException(
                    "当前账户不属于本次Calculate注销操作，不能执行" + stepCode.trim());
        }
    }

    private static RuntimeException requireActive(
            CalculateAccountLifecycleProjection projection, CalculateWriteAction action) {
        RuntimeException unknown = requireKnownProjection(projection, action);
        if (unknown != null) return unknown;
        if (projection.getBusinessStatus() == null || projection.getBusinessStatus() != 0
                || status(projection) != CalculateLifecycleStatus.ACTIVE) {
            return new CalculateLifecycleBlockedException("账户当前状态不允许执行" + action);
        }
        return null;
    }

    private static RuntimeException requireKnownProjection(
            CalculateAccountLifecycleProjection projection, CalculateWriteAction action) {
        if (projection == null) {
            return new CalculateLifecycleUnknownException(
                    "账户生命周期投影缺失，暂不能执行" + action);
        }
        try {
            status(projection);
            return null;
        } catch (RuntimeException ex) {
            return new CalculateLifecycleUnknownException("账户生命周期投影状态未知", ex);
        }
    }

    private Mode mode() {
        try {
            return Mode.valueOf(properties.getMode() == null
                    ? "SHADOW" : properties.getMode().trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("未知的Calculate生命周期写围栏模式", ex);
        }
    }

    private static CalculateLifecycleStatus status(CalculateAccountLifecycleProjection projection) {
        return CalculateLifecycleStatus.valueOf(projection.getLifecycleStatus());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private enum Mode {
        OFF,
        SHADOW,
        ENFORCE
    }
}
