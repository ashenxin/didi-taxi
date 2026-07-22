package com.sx.order.lifecycle.service;

import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleEventInboxMapper;
import com.sx.order.lifecycle.exception.AccountLifecycleUnknownException;
import com.sx.order.lifecycle.exception.OrderLifecycleProjectionConflictException;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.model.OrderAccountLifecycleProjection;
import com.sx.order.lifecycle.model.OrderAccountLifecycleEventInbox;
import com.sx.order.lifecycle.model.OrderLifecycleStatus;
import com.sx.order.lifecycle.metrics.OrderLifecycleMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class OrderLifecycleProjectionService {
    private final OrderAccountLifecycleProjectionMapper mapper;
    private final OrderAccountLifecycleEventInboxMapper eventInboxes;
    private final OrderLifecycleMetrics metrics;

    public OrderLifecycleProjectionService(OrderAccountLifecycleProjectionMapper mapper,
                                           OrderAccountLifecycleEventInboxMapper eventInboxes,
                                           OrderLifecycleMetrics metrics) {
        this.mapper = mapper;
        this.eventInboxes = eventInboxes;
        this.metrics = metrics;
    }

    @Transactional
    public ProjectionApplyResult apply(ApplyOrderLifecycleProjectionCommand command) {
        return applyUnderLock(command);
    }

    public ProjectionApplyResult applyUnderLock(ApplyOrderLifecycleProjectionCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("账户生命周期投影更新必须在事务内执行");
        }
        try {
            return applyClaimedEvent(command);
        } catch (OrderLifecycleProjectionConflictException ex) {
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.CONFLICT);
            throw ex;
        } catch (RuntimeException ex) {
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.UNKNOWN);
            throw ex;
        }
    }

    private ProjectionApplyResult applyClaimedEvent(ApplyOrderLifecycleProjectionCommand command) {
        parseStatus(command.lifecycleStatus());
        String requestHash = requestHash(command);
        EventClaim claim = claimEvent(command, requestHash);
        OrderAccountLifecycleProjection current = mapper.selectForUpdate(command.customerId());
        if (!claim.claimed()) {
            requireProjectionNotBehind(current, claim.event());
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.REPLAYED);
            return ProjectionApplyResult.REPLAYED;
        }
        if (current == null) {
            throw new AccountLifecycleUnknownException("账户生命周期投影不存在，必须先完成显式回填");
        }

        if (sameEvent(current, command)) {
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.REPLAYED);
            return ProjectionApplyResult.REPLAYED;
        }
        if (Objects.equals(current.getSourceEventId(), command.sourceEventId().trim())) {
            throw conflict("同一sourceEventId不能携带不同内容");
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
        metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.APPLIED);
        return ProjectionApplyResult.APPLIED;
    }

    @Transactional
    public ProjectionApplyResult seedActive(long customerId, String sourceEventId,
                                            java.time.LocalDateTime updatedAt) {
        try {
            return seedActiveInTransaction(customerId, sourceEventId, updatedAt);
        } catch (OrderLifecycleProjectionConflictException ex) {
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.CONFLICT);
            throw ex;
        } catch (RuntimeException ex) {
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.UNKNOWN);
            throw ex;
        }
    }

    private ProjectionApplyResult seedActiveInTransaction(long customerId, String sourceEventId,
                                                           java.time.LocalDateTime updatedAt) {
        ApplyOrderLifecycleProjectionCommand command = new ApplyOrderLifecycleProjectionCommand(
                customerId, 0, OrderLifecycleStatus.ACTIVE.name(), 0,
                null, sourceEventId, updatedAt);
        String requestHash = requestHash(command);
        EventClaim claim = claimEvent(command, requestHash);
        OrderAccountLifecycleProjection current = mapper.selectForUpdate(customerId);
        if (!claim.claimed()) {
            requireProjectionNotBehind(current, claim.event());
            metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.REPLAYED);
            return ProjectionApplyResult.REPLAYED;
        }
        if (current != null) {
            if (sameEvent(current, command)) {
                metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.REPLAYED);
                return ProjectionApplyResult.REPLAYED;
            }
            throw conflict("账户生命周期投影已经存在，不能重复回填");
        }
        if (mapper.insert(toProjection(command)) != 1) {
            throw new IllegalStateException("账户生命周期投影回填失败");
        }
        metrics.projectionApply(OrderLifecycleMetrics.ProjectionResult.APPLIED);
        return ProjectionApplyResult.APPLIED;
    }

    private void replayOrConflict(OrderAccountLifecycleEventInbox event, String requestHash) {
        if (!Objects.equals(event.getRequestHash(), requestHash)) {
            throw conflict("同一sourceEventId不能携带不同内容");
        }
    }

    private EventClaim claimEvent(ApplyOrderLifecycleProjectionCommand command, String requestHash) {
        String sourceEventId = command.sourceEventId().trim();
        OrderAccountLifecycleEventInbox existing = eventInboxes.selectById(sourceEventId);
        if (existing != null) {
            replayOrConflict(existing, requestHash);
            return new EventClaim(false, existing);
        }
        OrderAccountLifecycleEventInbox claimed = new OrderAccountLifecycleEventInbox()
                .setSourceEventId(command.sourceEventId().trim())
                .setCustomerId(command.customerId())
                .setLifecycleVersion(command.lifecycleVersion())
                .setRequestHash(requestHash)
                .setCreatedAt(command.updatedAt());
        try {
            if (eventInboxes.insert(claimed) != 1) {
                throw new IllegalStateException("账户生命周期事件占位失败");
            }
            return new EventClaim(true, claimed);
        } catch (DuplicateKeyException ex) {
            OrderAccountLifecycleEventInbox raced = eventInboxes.selectForUpdate(sourceEventId);
            if (raced == null) {
                throw new AccountLifecycleUnknownException("账户生命周期事件并发处理结果未知");
            }
            replayOrConflict(raced, requestHash);
            return new EventClaim(false, raced);
        }
    }

    private static void requireProjectionNotBehind(OrderAccountLifecycleProjection current,
                                                   OrderAccountLifecycleEventInbox event) {
        if (current == null || current.getLifecycleVersion() < event.getLifecycleVersion()) {
            throw new AccountLifecycleUnknownException("生命周期事件已处理但账户投影缺失或倒退");
        }
    }

    private record EventClaim(boolean claimed, OrderAccountLifecycleEventInbox event) {}

    private static String requestHash(ApplyOrderLifecycleProjectionCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{Long.toString(command.customerId()),
                    Integer.toString(command.businessStatus()), parseStatus(command.lifecycleStatus()).name(),
                    Long.toString(command.lifecycleVersion()), blankToNull(command.operationNo()),
                    command.sourceEventId().trim()}) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) ';');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持SHA-256", ex);
        }
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
