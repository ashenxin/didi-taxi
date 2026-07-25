package com.sx.wallet.lifecycle.service;

import com.sx.wallet.lifecycle.dao.WalletLifecycleParticipantInboxMapper;
import com.sx.wallet.lifecycle.dao.WalletLifecycleProjectionMapper;
import com.sx.wallet.lifecycle.exception.WalletLifecycleBlockedException;
import com.sx.wallet.lifecycle.exception.WalletLifecycleUnknownException;
import com.sx.wallet.lifecycle.model.WalletLifecycleParticipantInbox;
import com.sx.wallet.lifecycle.model.WalletLifecycleProjection;
import com.sx.wallet.lifecycle.model.WalletLifecycleStatus;
import com.sx.wallet.lifecycle.metrics.WalletLifecycleMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@Slf4j
public class WalletAccountWriteFence {
    private final WalletLifecycleProjectionMapper projections;
    private final WalletLifecycleParticipantInboxMapper inboxes;
    private final WalletLifecycleWriteFenceProperties properties;
    private final WalletLifecycleMetrics metrics;

    public WalletAccountWriteFence(WalletLifecycleProjectionMapper projections,
                                   WalletLifecycleParticipantInboxMapper inboxes,
                                   WalletLifecycleWriteFenceProperties properties,
                                   WalletLifecycleMetrics metrics) {
        this.projections = projections;
        this.inboxes = inboxes;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void lockAndRequireActive(long customerId, String action) {
        evaluate(customerId, action);
    }

    public void lockAndRequireResolvable(long customerId, String action) {
        Mode mode = mode();
        if (mode == Mode.OFF) return;
        WalletLifecycleProjection p = projections.selectForUpdate(customerId);
        RuntimeException rejection = known(p, action);
        handle(mode, customerId, action, rejection);
    }

    public void lockAndRequirePaymentAttemptAllowed(long customerId) {
        Mode mode = mode();
        if (mode == Mode.OFF) return;
        WalletLifecycleProjection p = projections.selectForUpdate(customerId);
        RuntimeException rejection = known(p, "DEBT_PAYMENT");
        if (rejection == null && status(p) == WalletLifecycleStatus.CANCELLED) {
            rejection = new WalletLifecycleBlockedException("已注销账户不能创建支付尝试");
        }
        if (rejection == null && status(p) == WalletLifecycleStatus.CANCELLING) {
            WalletLifecycleParticipantInbox finalCheck =
                    inboxes.findForUpdate(p.getOperationNo(), "WALLET_FINAL_CHECK");
            if (finalCheck != null && "COMPLETED".equals(finalCheck.getStatus())
                    && "PASS".equals(finalCheck.getDecision())) {
                rejection = new WalletLifecycleBlockedException(
                        "Wallet最终检查已通过，不能再新增支付尝试");
            }
        }
        handle(mode, customerId, "DEBT_PAYMENT", rejection);
    }

    public void lockAndRequireCurrentCancellation(long customerId, String operationNo) {
        WalletLifecycleProjection p = projections.selectForUpdate(customerId);
        if (p == null) throw new WalletLifecycleUnknownException("Wallet生命周期投影缺失");
        if (status(p) != WalletLifecycleStatus.CANCELLING
                || !Objects.equals(normalize(operationNo), normalize(p.getOperationNo()))) {
            throw new WalletLifecycleBlockedException("当前账户不属于本次Wallet注销操作");
        }
    }

    private void evaluate(long customerId, String action) {
        Mode mode = mode();
        if (mode == Mode.OFF) return;
        WalletLifecycleProjection p = projections.selectForUpdate(customerId);
        RuntimeException rejection = known(p, action);
        if (rejection == null && (p.getBusinessStatus() == null || p.getBusinessStatus() != 0
                || status(p) != WalletLifecycleStatus.ACTIVE)) {
            rejection = new WalletLifecycleBlockedException("账户当前状态不允许执行" + action);
        }
        handle(mode, customerId, action, rejection);
    }

    private void handle(Mode mode, long customerId, String action, RuntimeException rejection) {
        if (rejection == null) {
            metrics.fence(action, "ALLOW");
            return;
        }
        if (mode == Mode.SHADOW) {
            log.warn("wallet lifecycle write fence shadow rejection customerId={} action={} reason={}",
                    customerId, action, rejection.getClass().getSimpleName());
            metrics.fence(action, "UNKNOWN");
            return;
        }
        metrics.fence(action, rejection instanceof WalletLifecycleBlockedException
                ? "BLOCKED" : "UNKNOWN");
        throw rejection;
    }

    private static RuntimeException known(WalletLifecycleProjection p, String action) {
        if (p == null) return new WalletLifecycleUnknownException(
                "账户生命周期投影缺失，暂不能执行" + action);
        try { status(p); return null; }
        catch (RuntimeException ex) {
            return new WalletLifecycleUnknownException("账户生命周期投影状态未知", ex);
        }
    }

    private Mode mode() {
        try {
            return Mode.valueOf(properties.getMode() == null ? "SHADOW"
                    : properties.getMode().trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("未知Wallet生命周期写栅栏模式", ex);
        }
    }

    private static WalletLifecycleStatus status(WalletLifecycleProjection p) {
        return WalletLifecycleStatus.valueOf(p.getLifecycleStatus());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private enum Mode { OFF, SHADOW, ENFORCE }
}
