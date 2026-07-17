package com.sx.order.model.dto;

import java.math.BigDecimal;

public record PaymentAttemptRequest(String orderNo, Long passengerId, BigDecimal amount,
                                    String triggerType, String channel, String idempotencyKey) {
}
