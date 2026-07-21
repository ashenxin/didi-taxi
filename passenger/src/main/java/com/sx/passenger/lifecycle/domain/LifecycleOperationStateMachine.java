package com.sx.passenger.lifecycle.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LifecycleOperationStateMachine {

    private static final Map<LifecycleOperationType, Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>>>
            TRANSITIONS = transitions();

    public void requireTransition(LifecycleOperationType type,
                                  LifecycleOperationStatus from,
                                  LifecycleOperationStatus to,
                                  boolean irreversibleStarted) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
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

    private static Map<LifecycleOperationType, Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>>>
    transitions() {
        Map<LifecycleOperationType, Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>>> byType =
                new EnumMap<>(LifecycleOperationType.class);

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

    private static Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> immutable(
            Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> source) {
        Map<LifecycleOperationStatus, Set<LifecycleOperationStatus>> copy =
                new EnumMap<>(LifecycleOperationStatus.class);
        source.forEach((status, targets) -> copy.put(status, Set.copyOf(targets)));
        return Map.copyOf(copy);
    }
}
