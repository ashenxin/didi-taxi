package com.sx.wallet.lifecycle;

import com.sx.wallet.lifecycle.metrics.WalletLifecycleMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WalletLifecycleMetricsTest {
    @Test
    void metricsUseAllowlistedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WalletLifecycleMetrics metrics = new WalletLifecycleMetrics(registry);
        metrics.query("WALLET_FINAL_CHECK", "FOUND");
        metrics.query("operation-123", "customer-123");

        assertThat(registry.get("wallet.lifecycle.result.query")
                .tags("stepCode", "wallet_final_check", "result", "found")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("wallet.lifecycle.result.query")
                .tags("stepCode", "unknown", "result", "unknown")
                .counter().count()).isEqualTo(1);
    }
}
