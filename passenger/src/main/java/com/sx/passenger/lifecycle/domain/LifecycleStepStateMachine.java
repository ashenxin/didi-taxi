package com.sx.passenger.lifecycle.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LifecycleStepStateMachine {

    private static final Map<LifecycleStepStatus, Set<LifecycleStepStatus>> TRANSITIONS = transitions();

    public void requireTransition(LifecycleStepStatus from, LifecycleStepStatus to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Set<LifecycleStepStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new InvalidLifecycleTransitionException(
                    "Invalid lifecycle step transition: from=" + from + ", to=" + to + ", reason=NOT_ALLOWED");
        }
    }

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
