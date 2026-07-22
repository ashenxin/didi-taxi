package com.sx.order.lifecycle.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;

@Component
public class OrderLifecycleMetrics {
    public enum WriteFenceDecision { ALLOW, BLOCKED, UNKNOWN }
    public enum ProjectionResult { APPLIED, REPLAYED, CONFLICT, UNKNOWN }
    public enum ParticipantDecision { PASS, BLOCKED, UNKNOWN, CONFLICT }

    private final MeterRegistry registry;

    public OrderLifecycleMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void writeFence(String actionCode, WriteFenceDecision decision) {
        Runnable record = () -> Counter.builder("order.lifecycle.write_fence")
                .tag("actionCode", allowedAction(actionCode))
                .tag("decision", tag(decision))
                .register(registry).increment();
        if (decision == WriteFenceDecision.ALLOW) {
            afterCommitOrUnknown(record, () -> writeFence(actionCode, WriteFenceDecision.UNKNOWN));
        } else {
            bestEffort(record);
        }
    }

    public void projectionApply(ProjectionResult result) {
        Runnable record = () -> Counter.builder("order.lifecycle.projection.apply")
                .tag("result", tag(result)).register(registry).increment();
        if (result == ProjectionResult.APPLIED || result == ProjectionResult.REPLAYED) {
            afterCommitOrUnknown(record, () -> projectionApply(ProjectionResult.UNKNOWN));
        } else {
            bestEffort(record);
        }
    }

    public void participantCommand(String stepCode, ParticipantDecision decision) {
        Runnable record = () -> Counter.builder("order.lifecycle.participant.command")
                .tag("stepCode", "ORDER_FINAL_CHECK".equals(stepCode) ? "order_final_check" : "unknown")
                .tag("decision", tag(decision)).register(registry).increment();
        if (decision == ParticipantDecision.PASS || decision == ParticipantDecision.BLOCKED) {
            afterCommitOrUnknown(record,
                    () -> participantCommand(stepCode, ParticipantDecision.UNKNOWN));
        } else {
            bestEffort(record);
        }
    }

    private static void afterCommitOrUnknown(Runnable committed, Runnable rolledBack) {
        bestEffort(() -> {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                bestEffort(committed);
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bestEffort(committed);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) bestEffort(rolledBack);
                }
            });
        });
    }

    private static String allowedAction(String actionCode) {
        return "RIDE_CREATE".equals(actionCode) ? "ride_create" : "unknown";
    }

    private static String tag(Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }

    private static void bestEffort(Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException ignored) {
            // 可观测性失败不得改变订单或生命周期事务裁决。
        }
    }
}
