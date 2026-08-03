package com.sx.wallet.lifecycle.service;

import com.sx.wallet.lifecycle.dao.WalletLifecycleEventInboxMapper;
import com.sx.wallet.lifecycle.dao.WalletLifecycleProjectionMapper;
import com.sx.wallet.lifecycle.exception.WalletLifecycleProjectionConflictException;
import com.sx.wallet.lifecycle.exception.WalletLifecycleUnknownException;
import com.sx.wallet.lifecycle.model.ApplyWalletLifecycleProjectionCommand;
import com.sx.wallet.lifecycle.model.WalletLifecycleCommand;
import com.sx.wallet.lifecycle.model.WalletLifecycleEventInbox;
import com.sx.wallet.lifecycle.model.WalletLifecycleProjection;
import com.sx.wallet.lifecycle.model.WalletLifecycleStatus;
import com.sx.wallet.lifecycle.metrics.WalletLifecycleMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class WalletLifecycleProjectionService {
    private final WalletLifecycleProjectionMapper projections;
    private final WalletLifecycleEventInboxMapper events;
    private final WalletLifecycleRequestHasher hasher;
    private final WalletLifecycleMetrics metrics;

    public WalletLifecycleProjectionService(WalletLifecycleProjectionMapper projections,
                                            WalletLifecycleEventInboxMapper events,
                                            WalletLifecycleRequestHasher hasher,
                                            WalletLifecycleMetrics metrics) {
        this.projections = projections;
        this.events = events;
        this.hasher = hasher;
        this.metrics = metrics;
    }

    @Transactional
    public String apply(ApplyWalletLifecycleProjectionCommand command) {
        return applyUnderLock(command);
    }

    public String applyUnderLock(WalletLifecycleCommand command) {
        return applyUnderLock(toProjectionCommand(command));
    }

    public String applyUnderLock(ApplyWalletLifecycleProjectionCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Wallet生命周期投影更新必须在事务内执行");
        }
        WalletLifecycleStatus target = parse(command.lifecycleStatus());
        String hash = hasher.hashProjection(command);
        WalletLifecycleEventInbox event = events.selectById(command.sourceEventId().trim());
        if (event != null) {
            replayOrConflict(event, hash);
            WalletLifecycleProjection current = projections.selectForUpdate(command.customerId());
            if (current == null || current.getLifecycleVersion() < event.getLifecycleVersion()) {
                throw new WalletLifecycleUnknownException("事件已处理但Wallet投影缺失或倒退");
            }
            metrics.projection("REPLAYED");
            return "REPLAYED";
        }
        try {
            events.insert(new WalletLifecycleEventInbox()
                    .setSourceEventId(command.sourceEventId().trim())
                    .setCustomerId(command.customerId())
                    .setLifecycleVersion(command.lifecycleVersion())
                    .setRequestHash(hash)
                    .setCreatedAt(command.updatedAt()));
        } catch (DuplicateKeyException ex) {
            WalletLifecycleEventInbox raced = events.selectForUpdate(command.sourceEventId().trim());
            if (raced == null) throw new WalletLifecycleUnknownException("投影事件并发结果未知", ex);
            replayOrConflict(raced, hash);
            requireProjectionNotBehind(projections.selectForUpdate(command.customerId()), raced);
            metrics.projection("REPLAYED");
            return "REPLAYED";
        }
        WalletLifecycleProjection current = projections.selectForUpdate(command.customerId());
        if (current == null) throw new WalletLifecycleUnknownException("Wallet生命周期投影缺失，必须先回填");
        if ("CANCELLED".equals(current.getLifecycleStatus())) {
            throw conflict("已注销投影是终态");
        }
        if (command.lifecycleVersion() <= current.getLifecycleVersion()) {
            throw conflict("生命周期版本过期或内容冲突");
        }
        WalletLifecycleProjection next = toProjection(command, target);
        if (projections.updateWithVersion(next, current.getRowVersion()) != 1) {
            throw conflict("Wallet生命周期投影并发更新冲突");
        }
        metrics.projection("APPLIED");
        return "APPLIED";
    }

    /** 重检只确认既有栅栏仍精确属于当前 Operation，不再次推进生命周期版本。 */
    public void requireCurrentTarget(WalletLifecycleCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Wallet生命周期投影校验必须在事务内执行");
        }
        WalletLifecycleStatus target = parse(command.targetLifecycleStatus());
        WalletLifecycleProjection current = projections.selectForUpdate(command.customerId());
        if (current == null
                || !Objects.equals(current.getBusinessStatus(), 0)
                || !Objects.equals(current.getLifecycleStatus(), target.name())
                || !Objects.equals(current.getLifecycleVersion(), command.lifecycleVersion())
                || !Objects.equals(current.getOperationNo(), blank(command.operationNo()))) {
            throw conflict("生命周期重检目标与当前Wallet投影不一致");
        }
    }

    @Transactional
    public String seedActive(long customerId, String sourceEventId, LocalDateTime now) {
        ApplyWalletLifecycleProjectionCommand command = new ApplyWalletLifecycleProjectionCommand(
                customerId, 0, "ACTIVE", 0, null, sourceEventId, now);
        String hash = hasher.hashProjection(command);
        WalletLifecycleEventInbox event = events.selectById(sourceEventId);
        if (event != null) {
            replayOrConflict(event, hash);
            requireProjectionNotBehind(projections.selectForUpdate(customerId), event);
            metrics.projection("REPLAYED");
            return "REPLAYED";
        }
        events.insert(new WalletLifecycleEventInbox().setSourceEventId(sourceEventId)
                .setCustomerId(customerId).setLifecycleVersion(0L)
                .setRequestHash(hash).setCreatedAt(now));
        if (projections.selectForUpdate(customerId) != null) {
            throw conflict("Wallet生命周期投影已经存在");
        }
        projections.insert(toProjection(command, WalletLifecycleStatus.ACTIVE));
        metrics.projection("APPLIED");
        return "APPLIED";
    }

    private static WalletLifecycleProjection toProjection(
            ApplyWalletLifecycleProjectionCommand command, WalletLifecycleStatus status) {
        return new WalletLifecycleProjection().setCustomerId(command.customerId())
                .setBusinessStatus(command.businessStatus()).setLifecycleStatus(status.name())
                .setLifecycleVersion(command.lifecycleVersion())
                .setOperationNo(blank(command.operationNo()))
                .setSourceEventId(command.sourceEventId().trim())
                .setRowVersion(0L).setUpdatedAt(command.updatedAt());
    }

    private static ApplyWalletLifecycleProjectionCommand toProjectionCommand(
            WalletLifecycleCommand command) {
        return new ApplyWalletLifecycleProjectionCommand(
                command.customerId(), 0, command.targetLifecycleStatus(), command.lifecycleVersion(),
                command.operationNo(), command.sourceEventId(), command.requestedAt());
    }

    private static void replayOrConflict(WalletLifecycleEventInbox event, String hash) {
        if (!Objects.equals(event.getRequestHash(), hash)) {
            throw conflict("同一sourceEventId不能携带不同内容");
        }
    }

    private static void requireProjectionNotBehind(
            WalletLifecycleProjection projection, WalletLifecycleEventInbox event) {
        if (projection == null || projection.getLifecycleVersion() < event.getLifecycleVersion()) {
            throw new WalletLifecycleUnknownException("事件已处理但Wallet投影缺失或倒退");
        }
    }

    private static WalletLifecycleStatus parse(String status) {
        try { return WalletLifecycleStatus.valueOf(status.trim()); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("未知生命周期状态", ex); }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static WalletLifecycleProjectionConflictException conflict(String message) {
        return new WalletLifecycleProjectionConflictException(message);
    }
}
