package com.sx.passenger.auth.metrics;

import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PassengerAuthMetricsTest {

    @Test
    void metricRegistryFailuresNeverEscapePublicRecordMethods() {
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(mock(MeterRegistry.class));

        assertThatCode(() -> metrics.otpConsume(OtpPurpose.LOGIN, OtpConsumeResult.CONSUMED))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics.epochBump(PassengerAuthMetrics.EpochCause.LOGOUT,
                PassengerAuthMetrics.OperationResult.CONFLICT)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.lifecycleCasConflict(LifecycleOperationType.ACCOUNT_CANCEL))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.LOGOUT))
                .doesNotThrowAnyException();
    }

    @Test
    void transactionCallbacksNeverPropagateEpochMetricFailures() {
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(new SimpleMeterRegistry()) {
            @Override
            public void epochBump(EpochCause cause, OperationResult result) {
                throw new IllegalStateException("registry unavailable");
            }
        };

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatCode(() -> metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.AUTHENTICATION))
                    .doesNotThrowAnyException();
            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().getFirst();
            assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
            assertThatCode(() -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED))
                    .doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.AUTHENTICATION);
            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().getFirst();
            assertThatCode(() -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
                    .doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void synchronizationRegistrationFailureNeverEscapesBusinessCall() {
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(new SimpleMeterRegistry()) {
            @Override
            void registerSynchronization(TransactionSynchronization synchronization) {
                throw new IllegalStateException("synchronization unavailable");
            }
        };

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatCode(() -> metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.LOGOUT))
                    .doesNotThrowAnyException();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordsOnlyEnumBackedOtpEpochAndLifecycleTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(registry);

        metrics.otpConsume(OtpPurpose.ACCOUNT_CANCEL, OtpConsumeResult.CONSUMED);
        metrics.epochBump(PassengerAuthMetrics.EpochCause.AUTHENTICATION,
                PassengerAuthMetrics.OperationResult.SUCCESS);
        metrics.lifecycleCasConflict(LifecycleOperationType.PHONE_CHANGE);

        assertThat(registry.get("passenger.auth.otp.consume")
                .tag("purpose", "account_cancel").tag("result", "consumed").counter().count()).isEqualTo(1);
        assertThat(registry.get("passenger.auth.epoch.bump")
                .tag("cause", "authentication").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("passenger.lifecycle.cas.conflict")
                .tag("operationType", "phone_change").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getKey()).isIn("purpose", "result", "cause", "operationType")));
    }

    @Test
    void epochSuccessWaitsForCommitAndRollbackRecordsOnlyFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.AUTHENTICATION);
            assertThat(count(registry, "success")).isZero();
            assertThat(count(registry, "failure")).isZero();
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(count(registry, "success")).isZero();
            assertThat(count(registry, "failure")).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.observeEpochBump(PassengerAuthMetrics.EpochCause.AUTHENTICATION);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            assertThat(count(registry, "success")).isEqualTo(1);
            assertThat(count(registry, "failure")).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static double count(SimpleMeterRegistry registry, String result) {
        var counter = registry.find("passenger.auth.epoch.bump")
                .tags("cause", "authentication", "result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
