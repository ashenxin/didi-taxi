package com.sx.passenger.lifecycle.plan;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

class LifecyclePlanLoaderTest {

    @Test
    void loadsBothPackagedVersionOnePlans() {
        var plans = new LifecyclePlanLoader().load(
                new PathMatchingResourcePatternResolver(),
                "classpath*:account-lifecycle/*.yml");

        assertThat(plans).extracting(LoadedLifecyclePlan::sourceName)
                .containsExactlyInAnyOrder("account-cancel-v1.yml", "phone-change-v1.yml");
        assertThat(plans).extracting(plan -> plan.definition().operationType())
                .containsExactlyInAnyOrder("ACCOUNT_CANCEL", "PHONE_CHANGE");
    }
}
