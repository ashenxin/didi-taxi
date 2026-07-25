package com.sx.calculate.lifecycle.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

@Component
public class CalculateLifecycleMetrics {
    public enum WriteFenceDecision { ALLOW, BLOCKED, UNKNOWN }
    public enum ProjectionResult { APPLIED, REPLAYED, CONFLICT, UNKNOWN }
    public enum ParticipantDecision { PASS, BLOCKED, UNKNOWN, CONFLICT }
    public enum QueryResult { FOUND, NOT_FOUND, UNKNOWN }

    private static final Set<String> ACTIONS = Set.of(
            "COUPON_CLAIM", "COUPON_LOCK", "COUPON_USE", "COUPON_RELEASE", "BENEFIT_SIGN_IN");
    private static final Set<String> STEPS = Set.of(
            "CALCULATE_FINAL_CHECK", "CALCULATE_INVALIDATE_UNUSED_COUPONS", "CALCULATE_CLEAR_POINTS");

    private final MeterRegistry registry;

    public CalculateLifecycleMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void writeFence(String action, WriteFenceDecision decision) {
        Runnable record = () -> Counter.builder("calculate.lifecycle.write_fence")
                .tag("action", allow(action, ACTIONS))
                .tag("decision", tag(decision)).register(registry).increment();
        recordTransactional(decision == WriteFenceDecision.ALLOW, record,
                () -> writeFence(action, WriteFenceDecision.UNKNOWN));
    }

    public void projectionApply(ProjectionResult result) {
        Runnable record = () -> Counter.builder("calculate.lifecycle.projection.apply")
                .tag("result", tag(result)).register(registry).increment();
        recordTransactional(result == ProjectionResult.APPLIED || result == ProjectionResult.REPLAYED,
                record, () -> projectionApply(ProjectionResult.UNKNOWN));
    }

    public void participantCommand(String stepCode, ParticipantDecision decision) {
        Runnable record = () -> Counter.builder("calculate.lifecycle.participant.command")
                .tag("stepCode", allow(stepCode, STEPS))
                .tag("decision", tag(decision)).register(registry).increment();
        recordTransactional(decision == ParticipantDecision.PASS
                        || decision == ParticipantDecision.BLOCKED,
                record, () -> participantCommand(stepCode, ParticipantDecision.UNKNOWN));
    }

    public void resultQuery(String stepCode, QueryResult result) {
        bestEffort(() -> Counter.builder("calculate.lifecycle.result.query")
                .tag("stepCode", allow(stepCode, STEPS))
                .tag("result", tag(result)).register(registry).increment());
    }

    private static void recordTransactional(boolean waitForCommit, Runnable record, Runnable rolledBack) {
        if (!waitForCommit) {
            bestEffort(record);
            return;
        }
        bestEffort(() -> {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                bestEffort(record);
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bestEffort(record);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) bestEffort(rolledBack);
                }
            });
        });
    }

    private static String allow(String value, Set<String> allowlist) {
        return value != null && allowlist.contains(value.trim())
                ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String tag(Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }

    private static void bestEffort(Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException ignored) {
            // 可观测性失败不得改变生命周期或资产事务裁决。
        }
    }
}
