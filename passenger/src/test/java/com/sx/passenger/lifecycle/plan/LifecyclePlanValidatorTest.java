package com.sx.passenger.lifecycle.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecyclePlanValidatorTest {

    private final LifecyclePlanValidator validator = new LifecyclePlanValidator();

    @Test
    void rejectsUnknownParticipant() {
        LifecyclePlanDefinition plan = cancellation(step("ACCOUNT_FINALIZE_CANCEL", "UNKNOWN", "FINALIZE", 600));

        assertThatThrownBy(() -> validator.validate(plan, "unknown.yml"))
                .isInstanceOf(InvalidLifecyclePlanException.class)
                .hasMessageContaining("participant");
    }

    @Test
    void rejectsDuplicateStepsAndMissingFinalizer() {
        LifecycleStepDefinition check = step("ORDER_FINAL_CHECK", "ORDER", "PRECONDITION", 100);
        assertThatThrownBy(() -> validator.validate(cancellation(check, check), "duplicate.yml"))
                .isInstanceOf(InvalidLifecyclePlanException.class)
                .hasMessageContaining("duplicate step");
        assertThatThrownBy(() -> validator.validate(cancellation(check), "missing-finalizer.yml"))
                .isInstanceOf(InvalidLifecyclePlanException.class)
                .hasMessageContaining("ACCOUNT_FINALIZE_CANCEL");
    }

    private static LifecyclePlanDefinition cancellation(LifecycleStepDefinition... steps) {
        return new LifecyclePlanDefinition(1, "account-cancel", 1, "ACCOUNT_CANCEL", "ACTIVE", "test", List.of(steps));
    }

    private static LifecycleStepDefinition step(String code, String participant, String phase, int sequence) {
        return new LifecycleStepDefinition(code, participant, phase, "LOCAL_TRANSACTION", "REQUIRED",
                sequence, 10, new LifecycleRetryDefinition(0, 5));
    }
}
