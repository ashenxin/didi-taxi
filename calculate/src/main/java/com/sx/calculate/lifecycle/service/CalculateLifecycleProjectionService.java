package com.sx.calculate.lifecycle.service;

import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleEventInboxMapper;
import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleProjectionMapper;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleProjectionConflictException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleUnknownException;
import com.sx.calculate.lifecycle.model.ApplyCalculateLifecycleProjectionCommand;
import com.sx.calculate.lifecycle.model.CalculateAccountLifecycleEventInbox;
import com.sx.calculate.lifecycle.model.CalculateAccountLifecycleProjection;
import com.sx.calculate.lifecycle.model.CalculateLifecycleStatus;
import com.sx.calculate.lifecycle.metrics.CalculateLifecycleMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class CalculateLifecycleProjectionService {
    private final CalculateAccountLifecycleProjectionMapper projections;
    private final CalculateAccountLifecycleEventInboxMapper events;
    private final CalculateLifecycleRequestHasher hasher;
    private final CalculateLifecycleMetrics metrics;

    public CalculateLifecycleProjectionService(
            CalculateAccountLifecycleProjectionMapper projections,
            CalculateAccountLifecycleEventInboxMapper events,
            CalculateLifecycleRequestHasher hasher,
            CalculateLifecycleMetrics metrics) {
        this.projections = projections;
        this.events = events;
        this.hasher = hasher;
        this.metrics = metrics;
    }

    @Transactional
    public ProjectionApplyResult apply(ApplyCalculateLifecycleProjectionCommand command) {
        return applyUnderLock(command);
    }

    public ProjectionApplyResult applyUnderLock(ApplyCalculateLifecycleProjectionCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Calculate生命周期投影更新必须在事务内执行");
        }
        try {
            ProjectionApplyResult result = applyClaimed(command);
            metrics.projectionApply(result == ProjectionApplyResult.APPLIED
                    ? CalculateLifecycleMetrics.ProjectionResult.APPLIED
                    : CalculateLifecycleMetrics.ProjectionResult.REPLAYED);
            return result;
        } catch (CalculateLifecycleProjectionConflictException ex) {
            metrics.projectionApply(CalculateLifecycleMetrics.ProjectionResult.CONFLICT);
            throw ex;
        } catch (RuntimeException ex) {
            metrics.projectionApply(CalculateLifecycleMetrics.ProjectionResult.UNKNOWN);
            throw ex;
        }
    }

    /** 重检只确认首次终检建立的栅栏仍属于同一 Operation，不重复消费投影事件。 */
    public void requireCurrentTarget(ApplyCalculateLifecycleProjectionCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Calculate生命周期投影校验必须在事务内执行");
        }
        CalculateLifecycleStatus status = parseStatus(command.lifecycleStatus());
        CalculateAccountLifecycleProjection current =
                projections.selectForUpdate(command.customerId());
        if (current == null
                || !Objects.equals(current.getBusinessStatus(), command.businessStatus())
                || !Objects.equals(current.getLifecycleStatus(), status.name())
                || !Objects.equals(current.getLifecycleVersion(), command.lifecycleVersion())
                || !Objects.equals(current.getOperationNo(), blankToNull(command.operationNo()))) {
            throw conflict("生命周期重检目标与当前Calculate投影不一致");
        }
    }

    private ProjectionApplyResult applyClaimed(ApplyCalculateLifecycleProjectionCommand command) {
        CalculateLifecycleStatus status = parseStatus(command.lifecycleStatus());
        String requestHash = hasher.hashProjection(normalize(command, status));
        EventClaim claim = claimEvent(command, requestHash);
        CalculateAccountLifecycleProjection current = projections.selectForUpdate(command.customerId());
        if (!claim.claimed()) {
            requireProjectionNotBehind(current, claim.event());
            return ProjectionApplyResult.REPLAYED;
        }
        if (current == null) {
            throw new CalculateLifecycleUnknownException("Calculate生命周期投影不存在，必须先完成显式回填");
        }
        if (sameEvent(current, command, status)) return ProjectionApplyResult.REPLAYED;
        if (Objects.equals(current.getSourceEventId(), command.sourceEventId().trim())) {
            throw conflict("同一sourceEventId不能携带不同内容");
        }
        if (CalculateLifecycleStatus.CANCELLED.name().equals(current.getLifecycleStatus())) {
            throw conflict("已注销投影是终态，不能继续变更");
        }
        if (command.lifecycleVersion() < current.getLifecycleVersion()) {
            throw conflict("收到过期的账户生命周期版本");
        }
        if (command.lifecycleVersion() == current.getLifecycleVersion()) {
            throw conflict("同一账户生命周期版本的内容不一致");
        }
        CalculateAccountLifecycleProjection next = toProjection(command, status);
        if (projections.updateWithVersion(next, current.getRowVersion()) != 1) {
            throw conflict("Calculate生命周期投影并发更新冲突");
        }
        return ProjectionApplyResult.APPLIED;
    }

    @Transactional
    public ProjectionApplyResult seedActive(long customerId, String sourceEventId, LocalDateTime updatedAt) {
        ApplyCalculateLifecycleProjectionCommand command = new ApplyCalculateLifecycleProjectionCommand(
                customerId, 0, CalculateLifecycleStatus.ACTIVE.name(), 0,
                null, sourceEventId, updatedAt);
        String requestHash = hasher.hashProjection(command);
        EventClaim claim = claimEvent(command, requestHash);
        CalculateAccountLifecycleProjection current = projections.selectForUpdate(customerId);
        if (!claim.claimed()) {
            requireProjectionNotBehind(current, claim.event());
            metrics.projectionApply(CalculateLifecycleMetrics.ProjectionResult.REPLAYED);
            return ProjectionApplyResult.REPLAYED;
        }
        if (current != null) {
            if (sameEvent(current, command, CalculateLifecycleStatus.ACTIVE)) {
                metrics.projectionApply(CalculateLifecycleMetrics.ProjectionResult.REPLAYED);
                return ProjectionApplyResult.REPLAYED;
            }
            throw conflict("Calculate生命周期投影已经存在，不能重复回填");
        }
        if (projections.insert(toProjection(command, CalculateLifecycleStatus.ACTIVE)) != 1) {
            throw new IllegalStateException("Calculate生命周期投影回填失败");
        }
        metrics.projectionApply(CalculateLifecycleMetrics.ProjectionResult.APPLIED);
        return ProjectionApplyResult.APPLIED;
    }

    private EventClaim claimEvent(ApplyCalculateLifecycleProjectionCommand command, String requestHash) {
        String sourceEventId = command.sourceEventId().trim();
        CalculateAccountLifecycleEventInbox existing = events.selectById(sourceEventId);
        if (existing != null) {
            replayOrConflict(existing, requestHash);
            return new EventClaim(false, existing);
        }
        CalculateAccountLifecycleEventInbox claimed = new CalculateAccountLifecycleEventInbox()
                .setSourceEventId(sourceEventId)
                .setCustomerId(command.customerId())
                .setLifecycleVersion(command.lifecycleVersion())
                .setRequestHash(requestHash)
                .setCreatedAt(command.updatedAt());
        try {
            if (events.insert(claimed) != 1) {
                throw new IllegalStateException("Calculate生命周期事件占位失败");
            }
            return new EventClaim(true, claimed);
        } catch (DuplicateKeyException ex) {
            CalculateAccountLifecycleEventInbox raced = events.selectForUpdate(sourceEventId);
            if (raced == null) {
                throw new CalculateLifecycleUnknownException("Calculate生命周期事件并发处理结果未知", ex);
            }
            replayOrConflict(raced, requestHash);
            return new EventClaim(false, raced);
        }
    }

    private static void replayOrConflict(CalculateAccountLifecycleEventInbox event, String requestHash) {
        if (!Objects.equals(event.getRequestHash(), requestHash)) {
            throw conflict("同一sourceEventId不能携带不同内容");
        }
    }

    private static void requireProjectionNotBehind(CalculateAccountLifecycleProjection current,
                                                   CalculateAccountLifecycleEventInbox event) {
        if (current == null || current.getLifecycleVersion() < event.getLifecycleVersion()) {
            throw new CalculateLifecycleUnknownException("生命周期事件已处理但Calculate投影缺失或倒退");
        }
    }

    private static ApplyCalculateLifecycleProjectionCommand normalize(
            ApplyCalculateLifecycleProjectionCommand command, CalculateLifecycleStatus status) {
        return new ApplyCalculateLifecycleProjectionCommand(command.customerId(), command.businessStatus(),
                status.name(), command.lifecycleVersion(), blankToNull(command.operationNo()),
                command.sourceEventId().trim(), command.updatedAt());
    }

    private static CalculateAccountLifecycleProjection toProjection(
            ApplyCalculateLifecycleProjectionCommand command, CalculateLifecycleStatus status) {
        return new CalculateAccountLifecycleProjection()
                .setCustomerId(command.customerId())
                .setBusinessStatus(command.businessStatus())
                .setLifecycleStatus(status.name())
                .setLifecycleVersion(command.lifecycleVersion())
                .setOperationNo(blankToNull(command.operationNo()))
                .setSourceEventId(command.sourceEventId().trim())
                .setRowVersion(0L)
                .setUpdatedAt(command.updatedAt());
    }

    private static boolean sameEvent(CalculateAccountLifecycleProjection current,
                                     ApplyCalculateLifecycleProjectionCommand command,
                                     CalculateLifecycleStatus status) {
        return current.getLifecycleVersion() == command.lifecycleVersion()
                && current.getBusinessStatus() == command.businessStatus()
                && Objects.equals(current.getLifecycleStatus(), status.name())
                && Objects.equals(current.getOperationNo(), blankToNull(command.operationNo()))
                && Objects.equals(current.getSourceEventId(), command.sourceEventId().trim());
    }

    private static CalculateLifecycleStatus parseStatus(String value) {
        try {
            return CalculateLifecycleStatus.valueOf(value.trim());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("未知的账户生命周期状态: " + value, ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CalculateLifecycleProjectionConflictException conflict(String message) {
        return new CalculateLifecycleProjectionConflictException(message);
    }

    private record EventClaim(boolean claimed, CalculateAccountLifecycleEventInbox event) {}
}
