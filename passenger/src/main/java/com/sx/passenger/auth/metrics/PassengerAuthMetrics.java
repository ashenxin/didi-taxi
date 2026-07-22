package com.sx.passenger.auth.metrics;

import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.lifecycle.domain.LifecycleOperationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Objects;

@Component
public class PassengerAuthMetrics {

    public enum EpochCause {
        AUTHENTICATION("authentication"), LOGOUT("logout"),
        PHONE_CHANGE("phone_change"), ACCOUNT_CANCEL("account_cancel");
        private final String tag;
        EpochCause(String tag) { this.tag = tag; }
    }

    public enum OperationResult {
        SUCCESS("success"), CONFLICT("conflict"), REJECTED("rejected"), FAILURE("failure");
        private final String tag;
        OperationResult(String tag) { this.tag = tag; }
    }

    private final MeterRegistry registry;

    @Autowired
    public PassengerAuthMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public PassengerAuthMetrics() {
        this(Metrics.globalRegistry);
    }

    public void otpConsume(OtpPurpose purpose, OtpConsumeResult result) {
        Counter.builder("passenger.auth.otp.consume")
                .tag("purpose", purposeTag(purpose)).tag("result", result.name().toLowerCase(Locale.ROOT))
                .register(registry).increment();
    }

    public void epochBump(EpochCause cause, OperationResult result) {
        Counter.builder("passenger.auth.epoch.bump")
                .tag("cause", cause.tag).tag("result", result.tag).register(registry).increment();
    }

    /** 在 customer CAS 成功后调用；提交后记 success，事务回滚只记 failure。 */
    public void observeEpochBump(EpochCause cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            epochBump(cause, OperationResult.SUCCESS);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                epochBump(cause, OperationResult.SUCCESS);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    epochBump(cause, OperationResult.FAILURE);
                }
            }
        });
    }

    public void lifecycleCasConflict(LifecycleOperationType operationType) {
        Counter.builder("passenger.lifecycle.cas.conflict")
                .tag("operationType", operationTypeTag(operationType)).register(registry).increment();
    }

    private static String purposeTag(OtpPurpose purpose) {
        return switch (purpose) {
            case LOGIN -> "login";
            case PHONE_CHANGE_NEW_PHONE -> "phone_change_new_phone";
            case ACCOUNT_CANCEL -> "account_cancel";
        };
    }

    private static String operationTypeTag(LifecycleOperationType type) {
        return switch (type) {
            case PHONE_CHANGE -> "phone_change";
            case ACCOUNT_CANCEL -> "account_cancel";
        };
    }
}
