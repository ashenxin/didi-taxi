package com.sx.passenger.lifecycle.job;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LifecycleJobConfigurationValidatorTest {
    @Test
    void rejectsEnabledXxlWhenLifecycleMessagingIsDisabled() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("xxl.job.enabled", "true")
                .withProperty("passenger.account-lifecycle.messaging.enabled", "false");

        assertThatIllegalStateException().isThrownBy(
                new LifecycleJobConfigurationValidator(environment)::afterPropertiesSet)
                .withMessageContaining("必须开启生命周期Kafka");
    }

    @Test
    void relaxedProfileAllowsLocalDefaultToken() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("xxl.job.enabled", "true")
                .withProperty("passenger.account-lifecycle.messaging.enabled", "true")
                .withProperty("xxl.job.access-token", "default_token");
        environment.setActiveProfiles("local");

        assertThatCode(new LifecycleJobConfigurationValidator(environment)::afterPropertiesSet)
                .doesNotThrowAnyException();
    }

    @Test
    void productionRejectsWeakTokenAndAcceptsStrongToken() {
        MockEnvironment weak = new MockEnvironment()
                .withProperty("xxl.job.enabled", "true")
                .withProperty("passenger.account-lifecycle.messaging.enabled", "true")
                .withProperty("xxl.job.access-token", "default_token");
        assertThatIllegalStateException().isThrownBy(
                new LifecycleJobConfigurationValidator(weak)::afterPropertiesSet)
                .withMessageContaining("32 bytes");

        MockEnvironment strong = new MockEnvironment()
                .withProperty("xxl.job.enabled", "true")
                .withProperty("passenger.account-lifecycle.messaging.enabled", "true")
                .withProperty("xxl.job.access-token",
                        "xxl-prod-0123456789abcdef0123456789abcdef")
                .withProperty("xxl.job.admin.addresses",
                        "http://xxl-job-admin:8081/xxl-job-admin")
                .withProperty("xxl.job.executor.address",
                        "http://passenger-service:9995");
        assertThatCode(new LifecycleJobConfigurationValidator(strong)::afterPropertiesSet)
                .doesNotThrowAnyException();
    }

    @Test
    void productionRejectsLoopbackExecutorAddress() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("xxl.job.enabled", "true")
                .withProperty("passenger.account-lifecycle.messaging.enabled", "true")
                .withProperty("xxl.job.access-token",
                        "xxl-prod-0123456789abcdef0123456789abcdef")
                .withProperty("xxl.job.admin.addresses",
                        "http://xxl-job-admin:8081/xxl-job-admin")
                .withProperty("xxl.job.executor.address",
                        "http://127.0.0.1:9995");

        assertThatIllegalStateException().isThrownBy(
                new LifecycleJobConfigurationValidator(environment)::afterPropertiesSet)
                .withMessageContaining("production-routable");
    }
}
