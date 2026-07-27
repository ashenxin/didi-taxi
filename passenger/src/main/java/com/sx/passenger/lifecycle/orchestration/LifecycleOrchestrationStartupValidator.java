package com.sx.passenger.lifecycle.orchestration;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import com.sx.passenger.lifecycle.plan.LifecyclePlanRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时验证所有生效计划步骤都有可用参与者适配器。 */
@Component
public class LifecycleOrchestrationStartupValidator {
    private final LifecyclePlanRegistry plans;
    private final LifecycleParticipantRegistry participants;

    public LifecycleOrchestrationStartupValidator(
            LifecyclePlanRegistry plans, LifecycleParticipantRegistry participants) {
        this.plans = plans;
        this.participants = participants;
    }

    /** 缺少执行器时启动失败，避免运行后流程停在无法处理的步骤。 */
    @PostConstruct
    void validate() {
        plans.activePlan(LifecycleOperationType.ACCOUNT_CANCEL).steps().stream()
                .filter(step -> "SYNC_CHECK".equals(step.executionMode())
                        || "ASYNC_COMMAND".equals(step.executionMode()))
                .forEach(step -> {
                    if (!participants.supportsCommand(step.code(), step.participant())) {
                        throw new IllegalStateException(
                                "生命周期计划步骤缺少参与方执行器: " + step.code());
                    }
                });
    }
}
