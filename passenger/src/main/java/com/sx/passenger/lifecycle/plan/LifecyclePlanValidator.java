package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleCriticality;
import com.sx.passenger.lifecycle.domain.LifecycleExecutionMode;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.domain.LifecycleParticipantCode;
import com.sx.passenger.lifecycle.domain.LifecycleStepPhase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class LifecyclePlanValidator {
    private static final Pattern PLAN_CODE = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern STEP_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    public void validate(LifecyclePlanDefinition plan, String source) {
        require(plan != null, source, "plan is empty");
        require(Integer.valueOf(1).equals(plan.schemaVersion()), source, "schemaVersion must be 1");
        require(plan.code() != null && PLAN_CODE.matcher(plan.code()).matches(), source, "invalid plan code");
        require(plan.version() != null && plan.version() > 0, source, "version must be positive");
        LifecycleOperationType type = enumValue(LifecycleOperationType::valueOf, plan.operationType(), source, "operationType");
        require(Set.of("ACTIVE", "INACTIVE").contains(plan.status()), source, "invalid status");
        require(plan.steps() != null && !plan.steps().isEmpty(), source, "steps must not be empty");

        Set<String> codes = new HashSet<>();
        for (LifecycleStepDefinition step : plan.steps()) {
            require(step != null && step.code() != null && STEP_CODE.matcher(step.code()).matches(), source, "invalid step code");
            require(codes.add(step.code()), source, "duplicate step: " + step.code());
            enumValue(LifecycleParticipantCode::valueOf, step.participant(), source, "participant");
            enumValue(LifecycleStepPhase::valueOf, step.phase(), source, "phase");
            enumValue(LifecycleExecutionMode::valueOf, step.executionMode(), source, "executionMode");
            enumValue(LifecycleCriticality::valueOf, step.criticality(), source, "criticality");
            require(range(step.sequence(), 1, 100_000), source, "sequence out of range");
            require(range(step.timeoutSeconds(), 1, 300), source, "timeoutSeconds out of range");
            require(step.retry() != null && range(step.retry().maxAttempts(), 0, 20), source, "maxAttempts out of range");
            require(range(step.retry().initialIntervalSeconds(), 1, 3600), source, "initialIntervalSeconds out of range");
        }
        if (type == LifecycleOperationType.ACCOUNT_CANCEL) {
            validateCancellationFinalizer(plan.steps(), source);
        } else {
            long commits = plan.steps().stream().filter(s -> "IDENTITY_COMMIT_PHONE_CHANGE".equals(s.code())).count();
            require(commits == 1, source, "PHONE_CHANGE requires exactly one IDENTITY_COMMIT_PHONE_CHANGE");
            require(plan.steps().stream().noneMatch(s -> "ACCOUNT_FINALIZE_CANCEL".equals(s.code())),
                    source, "PHONE_CHANGE cannot contain ACCOUNT_FINALIZE_CANCEL");
        }
    }

    private static void validateCancellationFinalizer(List<LifecycleStepDefinition> steps, String source) {
        List<LifecycleStepDefinition> finalizers = steps.stream()
                .filter(step -> "ACCOUNT_FINALIZE_CANCEL".equals(step.code())).toList();
        require(finalizers.size() == 1, source, "ACCOUNT_CANCEL requires exactly one ACCOUNT_FINALIZE_CANCEL");
        LifecycleStepDefinition finalizer = finalizers.getFirst();
        require("ACCOUNT".equals(finalizer.participant()) && "FINALIZE".equals(finalizer.phase())
                        && "LOCAL_TRANSACTION".equals(finalizer.executionMode())
                        && "REQUIRED".equals(finalizer.criticality()),
                source, "invalid ACCOUNT_FINALIZE_CANCEL contract");
        int maximum = steps.stream().mapToInt(LifecycleStepDefinition::sequence).max().orElseThrow();
        require(finalizer.sequence() == maximum, source, "ACCOUNT_FINALIZE_CANCEL must be last");
    }

    private static boolean range(Integer value, int min, int max) {
        return value != null && value >= min && value <= max;
    }

    private static <T> T enumValue(java.util.function.Function<String, T> parser, String value,
                                   String source, String field) {
        try {
            return parser.apply(value);
        } catch (RuntimeException e) {
            throw invalid(source, "invalid " + field);
        }
    }

    private static void require(boolean condition, String source, String reason) {
        if (!condition) throw invalid(source, reason);
    }

    private static InvalidLifecyclePlanException invalid(String source, String reason) {
        return new InvalidLifecyclePlanException("Invalid lifecycle plan " + source + ": " + reason);
    }
}
