package com.sx.passenger.lifecycle.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生命周期 Step 状态机。
 *
 * <p>它限制单个参与者步骤的执行、重试、阻断解除和人工恢复路径，
 * 防止跳过 RUNNING 直接写成功，或把已经成功的步骤重新执行。
 */
public final class LifecycleStepStateMachine {

    /** 所有步骤共用的不可变状态迁移表。 */
    private static final Map<LifecycleStepStatus, Set<LifecycleStepStatus>> TRANSITIONS = transitions();

    /**
     * 校验一次步骤状态迁移。
     *
     * @throws InvalidLifecycleTransitionException 目标状态不在当前状态的允许集合中
     */
    public void requireTransition(LifecycleStepStatus from, LifecycleStepStatus to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Set<LifecycleStepStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new InvalidLifecycleTransitionException(
                    "Invalid lifecycle step transition: from=" + from + ", to=" + to + ", reason=NOT_ALLOWED");
        }
    }

    /** 构建步骤迁移图；SUCCEEDED、SKIPPED、CANCELLED 未登记，因此都是终态。 */
    private static Map<LifecycleStepStatus, Set<LifecycleStepStatus>> transitions() {
        Map<LifecycleStepStatus, Set<LifecycleStepStatus>> transitions =
                new EnumMap<>(LifecycleStepStatus.class);
        transitions.put(LifecycleStepStatus.PENDING,
                EnumSet.of(LifecycleStepStatus.RUNNING, LifecycleStepStatus.SKIPPED,
                        LifecycleStepStatus.CANCELLED));
        transitions.put(LifecycleStepStatus.RUNNING,
                EnumSet.of(LifecycleStepStatus.SUCCEEDED, LifecycleStepStatus.BLOCKED,
                        LifecycleStepStatus.RETRY_PENDING, LifecycleStepStatus.MANUAL_REVIEW,
                        LifecycleStepStatus.CANCELLED));
        transitions.put(LifecycleStepStatus.BLOCKED,
                EnumSet.of(LifecycleStepStatus.PENDING, LifecycleStepStatus.CANCELLED));
        transitions.put(LifecycleStepStatus.RETRY_PENDING,
                EnumSet.of(LifecycleStepStatus.RUNNING, LifecycleStepStatus.CANCELLED));
        transitions.put(LifecycleStepStatus.MANUAL_REVIEW,
                EnumSet.of(LifecycleStepStatus.RUNNING, LifecycleStepStatus.CANCELLED));
        Map<LifecycleStepStatus, Set<LifecycleStepStatus>> immutable =
                new EnumMap<>(LifecycleStepStatus.class);
        transitions.forEach((status, targets) -> immutable.put(status, Set.copyOf(targets)));
        return Map.copyOf(immutable);
    }
}
