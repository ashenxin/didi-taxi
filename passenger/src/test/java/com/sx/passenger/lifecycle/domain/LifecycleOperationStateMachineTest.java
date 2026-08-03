package com.sx.passenger.lifecycle.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleOperationStateMachineTest {

    private final LifecycleOperationStateMachine stateMachine = new LifecycleOperationStateMachine();

    @ParameterizedTest
    @MethodSource("validTransitions")
    void acceptsWhitelistedTransitions(LifecycleOperationType type,
                                       LifecycleOperationStatus from,
                                       LifecycleOperationStatus to) {
        assertThatCode(() -> stateMachine.requireTransition(type, from, to, false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAbortAfterIrreversibleWorkStarted() {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                LifecycleOperationType.ACCOUNT_CANCEL,
                LifecycleOperationStatus.BLOCKED,
                LifecycleOperationStatus.ABORTED,
                true))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("IRREVERSIBLE_STARTED");
    }

    @Test
    void rejectsPhoneChangeEnteringCancellationFence() {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                LifecycleOperationType.PHONE_CHANGE,
                LifecycleOperationStatus.REQUESTED,
                LifecycleOperationStatus.FENCED,
                false))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
    }

    @Test
    void rejectsLeavingTerminalStateAndSameStateTransition() {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                LifecycleOperationType.ACCOUNT_CANCEL,
                LifecycleOperationStatus.COMPLETED,
                LifecycleOperationStatus.EXECUTING,
                true))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
        assertThatThrownBy(() -> stateMachine.requireTransition(
                LifecycleOperationType.ACCOUNT_CANCEL,
                LifecycleOperationStatus.BLOCKED,
                LifecycleOperationStatus.BLOCKED,
                false))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
    }

    private static Stream<Arguments> validTransitions() {
        return Stream.of(
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "REQUESTED", "FENCED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "FENCED", "VALIDATING"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "FENCED", "ABORTED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "VALIDATING", "BLOCKED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "VALIDATING", "EXECUTING"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "VALIDATING", "RETRY_PENDING"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "VALIDATING", "MANUAL_REVIEW"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "VALIDATING", "ABORTED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "BLOCKED", "VALIDATING"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "BLOCKED", "ABORTED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "EXECUTING", "RETRY_PENDING"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "EXECUTING", "MANUAL_REVIEW"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "EXECUTING", "COMPLETED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "EXECUTING", "ABORTED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "RETRY_PENDING", "EXECUTING"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "RETRY_PENDING", "MANUAL_REVIEW"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "RETRY_PENDING", "ABORTED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "MANUAL_REVIEW", "EXECUTING"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "MANUAL_REVIEW", "COMPLETED"),
                transition(LifecycleOperationType.ACCOUNT_CANCEL, "MANUAL_REVIEW", "ABORTED"),
                transition(LifecycleOperationType.PHONE_CHANGE, "REQUESTED", "EXECUTING"),
                transition(LifecycleOperationType.PHONE_CHANGE, "EXECUTING", "RETRY_PENDING"),
                transition(LifecycleOperationType.PHONE_CHANGE, "EXECUTING", "MANUAL_REVIEW"),
                transition(LifecycleOperationType.PHONE_CHANGE, "EXECUTING", "COMPLETED"),
                transition(LifecycleOperationType.PHONE_CHANGE, "RETRY_PENDING", "EXECUTING"),
                transition(LifecycleOperationType.PHONE_CHANGE, "RETRY_PENDING", "MANUAL_REVIEW"),
                transition(LifecycleOperationType.PHONE_CHANGE, "MANUAL_REVIEW", "EXECUTING"),
                transition(LifecycleOperationType.PHONE_CHANGE, "MANUAL_REVIEW", "COMPLETED")
        );
    }

    private static Arguments transition(LifecycleOperationType type, String from, String to) {
        return Arguments.of(type,
                LifecycleOperationStatus.valueOf(from),
                LifecycleOperationStatus.valueOf(to));
    }
}
