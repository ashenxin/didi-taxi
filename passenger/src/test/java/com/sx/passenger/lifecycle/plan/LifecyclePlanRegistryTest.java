package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecyclePlanRegistryTest {

    @Test
    void resolvesActivePlanAndKeepsValidatedStepsImmutable() {
        LifecyclePlanDefinition source = phonePlan(1, "ACTIVE");
        LifecyclePlanRegistry registry = ImmutableLifecyclePlanRegistry.from(
                List.of(new LoadedLifecyclePlan("phone-v1.yml", source)),
                new LifecyclePlanValidator(), new LifecyclePlanDigest());

        ValidatedLifecyclePlan plan = registry.activePlan(LifecycleOperationType.PHONE_CHANGE);

        assertThat(plan.code()).isEqualTo("phone-change");
        assertThat(plan.version()).isEqualTo(1);
        assertThatThrownBy(() -> plan.steps().add(source.steps().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsTwoActivePlansForSameOperationType() {
        assertThatThrownBy(() -> ImmutableLifecyclePlanRegistry.from(
                List.of(new LoadedLifecyclePlan("v1.yml", phonePlan(1, "ACTIVE")),
                        new LoadedLifecyclePlan("v2.yml", phonePlan(2, "ACTIVE"))),
                new LifecyclePlanValidator(), new LifecyclePlanDigest()))
                .isInstanceOf(InvalidLifecyclePlanException.class)
                .hasMessageContaining("multiple ACTIVE");
    }

    private static LifecyclePlanDefinition phonePlan(int version, String status) {
        var step = new LifecycleStepDefinition("IDENTITY_COMMIT_PHONE_CHANGE", "IDENTITY", "ACTION",
                "LOCAL_TRANSACTION", "REQUIRED", 100, 10, new LifecycleRetryDefinition(0, 5));
        return new LifecyclePlanDefinition(1, "phone-change", version, "PHONE_CHANGE", status, "test", List.of(step));
    }
}
