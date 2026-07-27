package com.sx.passenger.lifecycle.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生命周期 Operation 状态机。
 *
 * <p>所有宏观状态变更都应先经过该状态机校验。换号与注销拥有不同的合法路径，
 * 且注销一旦开始不可逆步骤，就禁止迁移到 {@link LifecycleOperationStatus#ABORTED}。
 */
public final class LifecycleOperationStateMachine {

    /** 按操作类型保存的不可变状态迁移表。 */
    private static final Map<LifecycleOperationType, Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>>>
            TRANSITIONS = transitions();

    /**
     * 要求给定状态迁移合法，否则抛出领域异常。
     *
     * @param type 操作类型，不同类型使用不同迁移图
     * @param from 当前状态
     * @param to 目标状态
     * @param irreversibleStarted 是否已经执行过不可逆步骤
     */
    public void requireTransition(LifecycleOperationType type,
                                  LifecycleOperationStatus from,
                                  LifecycleOperationStatus to,
                                  boolean irreversibleStarted) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        // 不可逆动作开始后不能再宣称操作已安全撤销。
        if (irreversibleStarted && to == LifecycleOperationStatus.ABORTED) {
            throw invalid(type, from, to, "IRREVERSIBLE_STARTED");
        }
        Set<LifecycleOperationStatus> allowed = TRANSITIONS.get(type).get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw invalid(type, from, to, "NOT_ALLOWED");
        }
    }

    private static InvalidLifecycleTransitionException invalid(
            LifecycleOperationType type,
            LifecycleOperationStatus from,
            LifecycleOperationStatus to,
            String reason) {
        return new InvalidLifecycleTransitionException(
                "Invalid lifecycle operation transition: type=" + type
                        + ", from=" + from + ", to=" + to + ", reason=" + reason);
    }

    /** 构造换号和注销各自的完整迁移图。未登记的源状态视为终态。 */
    private static Map<LifecycleOperationType, Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>>>
    transitions() {
        Map<LifecycleOperationType, Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>>> byType =
                new EnumMap<>(LifecycleOperationType.class);

        // 注销先建立栅栏，再预检，最后进入实际执行或人工处置。
        Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> cancellation =
                new EnumMap<>(LifecycleOperationStatus.class);
        cancellation.put(LifecycleOperationStatus.REQUESTED, EnumSet.of(LifecycleOperationStatus.FENCED));
        cancellation.put(LifecycleOperationStatus.FENCED,
                EnumSet.of(LifecycleOperationStatus.VALIDATING, LifecycleOperationStatus.ABORTED));
        cancellation.put(LifecycleOperationStatus.VALIDATING,
                EnumSet.of(LifecycleOperationStatus.BLOCKED, LifecycleOperationStatus.EXECUTING,
                        LifecycleOperationStatus.RETRY_PENDING, LifecycleOperationStatus.MANUAL_REVIEW));
        cancellation.put(LifecycleOperationStatus.BLOCKED,
                EnumSet.of(LifecycleOperationStatus.VALIDATING, LifecycleOperationStatus.ABORTED));
        cancellation.put(LifecycleOperationStatus.EXECUTING,
                EnumSet.of(LifecycleOperationStatus.RETRY_PENDING, LifecycleOperationStatus.MANUAL_REVIEW,
                        LifecycleOperationStatus.COMPLETED));
        cancellation.put(LifecycleOperationStatus.RETRY_PENDING,
                EnumSet.of(LifecycleOperationStatus.EXECUTING, LifecycleOperationStatus.MANUAL_REVIEW));
        cancellation.put(LifecycleOperationStatus.MANUAL_REVIEW,
                EnumSet.of(LifecycleOperationStatus.EXECUTING, LifecycleOperationStatus.COMPLETED));
        byType.put(LifecycleOperationType.ACCOUNT_CANCEL, immutable(cancellation));

        // 换号不需要注销式栅栏和阻断阶段，受理后直接执行提交。
        Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> phoneChange =
                new EnumMap<>(LifecycleOperationStatus.class);
        phoneChange.put(LifecycleOperationStatus.REQUESTED, EnumSet.of(LifecycleOperationStatus.EXECUTING));
        phoneChange.put(LifecycleOperationStatus.EXECUTING,
                EnumSet.of(LifecycleOperationStatus.RETRY_PENDING, LifecycleOperationStatus.MANUAL_REVIEW,
                        LifecycleOperationStatus.COMPLETED));
        phoneChange.put(LifecycleOperationStatus.RETRY_PENDING,
                EnumSet.of(LifecycleOperationStatus.EXECUTING, LifecycleOperationStatus.MANUAL_REVIEW));
        phoneChange.put(LifecycleOperationStatus.MANUAL_REVIEW,
                EnumSet.of(LifecycleOperationStatus.EXECUTING, LifecycleOperationStatus.COMPLETED));
        byType.put(LifecycleOperationType.PHONE_CHANGE, immutable(phoneChange));
        return Map.copyOf(byType);
    }

    /** 深复制迁移集合，避免外部或初始化后的代码修改状态机规则。 */
    private static Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> immutable(
            Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> source) {
        Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> copy =
                new EnumMap<>(LifecycleOperationStatus.class);
        source.forEach((status, targets) -> copy.put(status, Set.copyOf(targets)));
        return Map.copyOf(copy);
    }
}
