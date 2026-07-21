package com.sx.passenger.lifecycle.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleStepStateMachineTest {

    private final LifecycleStepStateMachine stateMachine = new LifecycleStepStateMachine();

    @ParameterizedTest
    @MethodSource("validTransitions")
    void acceptsWhitelistedTransitions(LifecycleStepStatus from, LifecycleStepStatus to) {
        assertThatCode(() -> stateMachine.requireTransition(from, to)).doesNotThrowAnyException();
    }

    @Test
    void rejectsLeavingTerminalState() {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                LifecycleStepStatus.SUCCEEDED, LifecycleStepStatus.RUNNING))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
    }

    @Test
    void rejectsRetryReturningDirectlyToPending() {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                LifecycleStepStatus.RETRY_PENDING, LifecycleStepStatus.PENDING))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
    }

    private static Stream<Arguments> validTransitions() {
        return Stream.of(
                transition("PENDING", "RUNNING"),
                transition("PENDING", "SKIPPED"),
                transition("PENDING", "CANCELLED"),
                transition("RUNNING", "SUCCEEDED"),
                transition("RUNNING", "BLOCKED"),
                transition("RUNNING", "RETRY_PENDING"),
                transition("RUNNING", "MANUAL_REVIEW"),
                transition("RUNNING", "CANCELLED"),
                transition("BLOCKED", "PENDING"),
                transition("BLOCKED", "CANCELLED"),
                transition("RETRY_PENDING", "RUNNING"),
                transition("RETRY_PENDING", "CANCELLED"),
                transition("MANUAL_REVIEW", "RUNNING"),
                transition("MANUAL_REVIEW", "CANCELLED")
        );
    }

    private static Arguments transition(String from, String to) {
        return Arguments.of(LifecycleStepStatus.valueOf(from), LifecycleStepStatus.valueOf(to));
    }
}
