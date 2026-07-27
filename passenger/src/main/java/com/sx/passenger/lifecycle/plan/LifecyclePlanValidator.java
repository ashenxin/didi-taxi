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

/**
 * 生命周期计划的启动期强校验器。
 *
 * <p>除了字段格式和范围，还校验关键业务契约：注销必须有且仅有一个位于最后的本地
 * {@code ACCOUNT_FINALIZE_CANCEL}；换号必须有且仅有一个身份提交步骤，并且不能包含注销终结器。
 */
public final class LifecyclePlanValidator {
    /** 计划代码采用小写短横线格式，便于作为稳定配置标识。 */
    private static final Pattern PLAN_CODE = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    /** 步骤代码采用大写下划线格式，便于跨服务作为协议枚举使用。 */
    private static final Pattern STEP_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    /**
     * 校验一份计划的完整结构与操作类型专属约束。
     *
     * @param plan 待校验计划
     * @param source 配置来源名，仅用于错误定位
     */
    public void validate(LifecyclePlanDefinition plan, String source) {
        require(plan != null, source, "plan is empty");
        require(Integer.valueOf(1).equals(plan.schemaVersion()), source, "schemaVersion must be 1");
        require(plan.code() != null && PLAN_CODE.matcher(plan.code()).matches(), source, "invalid plan code");
        require(plan.version() != null && plan.version() > 0, source, "version must be positive");
        LifecycleOperationType type = enumValue(LifecycleOperationType::valueOf, plan.operationType(), source, "operationType");
        require(Set.of("ACTIVE", "INACTIVE").contains(plan.status()), source, "invalid status");
        require(plan.steps() != null && !plan.steps().isEmpty(), source, "steps must not be empty");

        // 步骤代码在同一计划内必须唯一，否则结果事件无法确定对应步骤。
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

    /** 校验注销最终提交步骤的位置和不可替换契约。 */
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

    /** 对可空 Integer 做闭区间检查。 */
    private static boolean range(Integer value, int min, int max) {
        return value != null && value >= min && value <= max;
    }

    /** 把字符串解析为领域枚举，并转换为包含计划来源的统一异常。 */
    private static <T> T enumValue(java.util.function.Function<String, T> parser, String value,
                                   String source, String field) {
        try {
            return parser.apply(value);
        } catch (RuntimeException e) {
            throw invalid(source, "invalid " + field);
        }
    }

    /** 统一布尔断言，保持所有配置错误的消息格式一致。 */
    private static void require(boolean condition, String source, String reason) {
        if (!condition) throw invalid(source, reason);
    }

    private static InvalidLifecyclePlanException invalid(String source, String reason) {
        return new InvalidLifecyclePlanException("Invalid lifecycle plan " + source + ": " + reason);
    }
}
