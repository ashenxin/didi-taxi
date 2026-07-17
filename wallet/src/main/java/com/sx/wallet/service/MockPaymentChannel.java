package com.sx.wallet.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MockPaymentChannel implements PaymentChannel {

    private final String autoStatus;

    public MockPaymentChannel(@Value("${wallet.payment.mock-auto-status:SUCCESS}") String autoStatus) {
        String normalized = autoStatus == null ? "SUCCESS" : autoStatus.trim().toUpperCase(Locale.ROOT);
        if (!"SUCCESS".equals(normalized) && !"FAILED".equals(normalized)
                && !"CONFIRMING".equals(normalized)) {
            throw new IllegalArgumentException("mock-auto-status仅支持SUCCESS/FAILED/CONFIRMING");
        }
        this.autoStatus = normalized;
    }

    @Override
    public ChannelResult initiate(ChannelCommand command) {
        if ("SUCCESS".equals(autoStatus)) {
            return new ChannelResult("SUCCESS", "MOCK_TRADE_" + command.channelRequestNo(), null);
        }
        if ("FAILED".equals(autoStatus)) {
            return new ChannelResult("FAILED", null, "MOCK_AUTO_PAY_FAILED");
        }
        return new ChannelResult("CONFIRMING", null, null);
    }
}
