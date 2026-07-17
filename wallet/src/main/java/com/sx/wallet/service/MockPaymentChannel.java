package com.sx.wallet.service;

import com.sx.wallet.config.MockPaymentProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MockPaymentChannel implements PaymentChannel {

    private final boolean enabled;
    private final String autoStatus;

    public MockPaymentChannel(MockPaymentProperties properties) {
        this.enabled = properties.isEnabled();
        String autoStatus = properties.getAutoStatus();
        String normalized = autoStatus == null ? "SUCCESS" : autoStatus.trim().toUpperCase(Locale.ROOT);
        if (!"SUCCESS".equals(normalized) && !"FAILED".equals(normalized)
                && !"CONFIRMING".equals(normalized)) {
            throw new IllegalArgumentException("mock-auto-status仅支持SUCCESS/FAILED/CONFIRMING");
        }
        this.autoStatus = normalized;
    }

    @Override
    public ChannelResult initiate(ChannelCommand command) {
        if (!enabled) {
            throw new IllegalStateException("mock支付未启用");
        }
        if ("SUCCESS".equals(autoStatus)) {
            return new ChannelResult("SUCCESS", "MOCK_TRADE_" + command.channelRequestNo(), null);
        }
        if ("FAILED".equals(autoStatus)) {
            return new ChannelResult("FAILED", null, "MOCK_AUTO_PAY_FAILED");
        }
        return new ChannelResult("CONFIRMING", null, null);
    }
}
