package com.sx.wallet.service;

import java.math.BigDecimal;

public interface PaymentChannel {

    ChannelResult initiate(ChannelCommand command);

    record ChannelCommand(String paymentNo,
                          String channelRequestNo,
                          String orderNo,
                          Long passengerId,
                          String channel,
                          BigDecimal amount) {
    }

    record ChannelResult(String status, String channelTradeNo, String failedReason) {
    }
}
