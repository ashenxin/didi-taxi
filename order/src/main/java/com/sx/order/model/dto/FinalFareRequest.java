package com.sx.order.model.dto;

public record FinalFareRequest(String fareRuleSnapshot,
                               String fareCalculationVersion,
                               Long billingDistanceMeters,
                               Long billingDurationSeconds) {
}
