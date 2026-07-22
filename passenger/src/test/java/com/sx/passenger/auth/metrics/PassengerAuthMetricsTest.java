package com.sx.passenger.auth.metrics;

import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PassengerAuthMetricsTest {

    @Test
    void recordsOnlyEnumBackedOtpEpochAndLifecycleTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(registry);

        metrics.otpConsume(OtpPurpose.ACCOUNT_CANCEL, OtpConsumeResult.CONSUMED);
        metrics.epochBump(PassengerAuthMetrics.EpochCause.LOGIN,
                PassengerAuthMetrics.OperationResult.SUCCESS);
        metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);

        assertThat(registry.get("passenger.auth.otp.consume")
                .tag("purpose", "account_cancel").tag("result", "consumed").counter().count()).isEqualTo(1);
        assertThat(registry.get("passenger.auth.epoch.bump")
                .tag("cause", "login").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("passenger.lifecycle.cas.conflict")
                .tag("operationType", "phone_change").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getKey()).isIn("purpose", "result", "cause", "operationType")));
    }
}
