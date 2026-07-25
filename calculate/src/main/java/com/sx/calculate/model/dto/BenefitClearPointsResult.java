package com.sx.calculate.model.dto;

public record BenefitClearPointsResult(
        int balanceBefore,
        int clearedPoints,
        int balanceAfter,
        String accountStatus,
        Long pointsFlowId) {
}
