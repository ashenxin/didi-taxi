package com.sx.wallet.service;

import com.sx.wallet.config.MockPaymentProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentChannelTest {

    @Test
    void disabledMockCannotInitiatePayment() {
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(false);
        MockPaymentChannel channel = new MockPaymentChannel(properties);

        assertThatThrownBy(() -> channel.initiate(new PaymentChannel.ChannelCommand(
                "PAY-1", "REQ-1", "ORDER-1", 10001L, "ALIPAY", new BigDecimal("30.00"))))
                .hasMessageContaining("mock支付未启用");
    }
}
