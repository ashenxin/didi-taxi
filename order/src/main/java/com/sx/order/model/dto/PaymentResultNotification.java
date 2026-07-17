package com.sx.order.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResultNotification(String paymentNo, String orderNo, Long passengerId,
                                        String channel, BigDecimal amount, String status,
                                        String channelTradeNo, LocalDateTime occurredAt) {
}
