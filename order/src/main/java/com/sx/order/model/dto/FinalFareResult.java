package com.sx.order.model.dto;

import java.math.BigDecimal;

public record FinalFareResult(BigDecimal finalAmount,
                              String fareCalculationVersion,
                              Long billingDistanceMeters,
                              Long billingDurationSeconds) {
}
