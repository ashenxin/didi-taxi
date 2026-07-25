package com.sx.wallet.lifecycle.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

@Component
public class WalletLifecycleMetrics {
    private static final Set<String> ACTIONS = Set.of(
            "AUTO_PAY_SIGN", "AUTO_PAY_MANAGE", "AUTO_PAY_CLOSE", "DEBT_PAYMENT");
    private static final Set<String> STEPS = Set.of(
            "WALLET_FINAL_CHECK", "WALLET_CLOSE_AUTO_PAY");
    private final MeterRegistry registry;

    public WalletLifecycleMetrics(MeterRegistry registry) { this.registry = registry; }

    public void fence(String action, String decision) {
        record("wallet.lifecycle.write_fence", "action", allow(action, ACTIONS),
                "decision", tag(decision), true);
    }

    public void projection(String result) {
        record("wallet.lifecycle.projection.apply", "result", tag(result),
                null, null, "APPLIED".equals(result) || "REPLAYED".equals(result));
    }

    public void participant(String step, String decision) {
        record("wallet.lifecycle.participant.command", "stepCode", allow(step, STEPS),
                "decision", tag(decision), true);
    }

    public void query(String step, String result) {
        recordNow("wallet.lifecycle.result.query", "stepCode", allow(step, STEPS),
                "result", tag(result));
    }

    private void record(String name, String key1, String value1, String key2,
                        String value2, boolean afterCommit) {
        Runnable recorder = () -> recordNow(name, key1, value1, key2, value2);
        try {
            if (afterCommit && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override public void afterCommit() { safe(recorder); }
                        });
            } else safe(recorder);
        } catch (RuntimeException ignored) {
        }
    }

    private void recordNow(String name, String key1, String value1,
                           String key2, String value2) {
        try {
            Counter.Builder builder = Counter.builder(name).tag(key1, value1);
            if (key2 != null) builder.tag(key2, value2);
            builder.register(registry).increment();
        } catch (RuntimeException ignored) {
        }
    }

    private static void safe(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { }
    }

    private static String allow(String value, Set<String> allowed) {
        return value != null && allowed.contains(value.trim())
                ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String tag(String value) {
        if (value == null) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z_]{1,32}") ? normalized : "unknown";
    }
}
