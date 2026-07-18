package com.sx.order.model.dto;

import java.math.BigDecimal;

public record CreateOrderPreflightResult(
        String decision,
        String orderNo,
        String blockingSettlementStatus,
        String blockingAction,
        Long plannedDistanceMeters,
        Long plannedDurationSeconds,
        String distanceSource,
        String routeMockVersion,
        BigDecimal estimatedAmount,
        Long fareRuleId,
        String fareRuleSnapshot,
        String fareCalculationVersion) {

    public static CreateOrderPreflightResult allowCreate() {
        return new CreateOrderPreflightResult("ALLOW_CREATE", null, null, null,
                null, null, null, null, null, null, null, null);
    }

    public static CreateOrderPreflightResult blocked(BlockingOrderResult blocking) {
        return new CreateOrderPreflightResult("BLOCKED", blocking.blockingOrderNo(),
                blocking.settlementStatus(), blocking.action(), null, null, null, null,
                null, null, null, null);
    }

    public static CreateOrderPreflightResult replay(TripOrderSnapshot snapshot) {
        return new CreateOrderPreflightResult("REPLAY_SUCCESS", snapshot.orderNo(), null, null,
                snapshot.plannedDistanceMeters(), snapshot.plannedDurationSeconds(),
                snapshot.distanceSource(), snapshot.routeMockVersion(), snapshot.estimatedAmount(),
                snapshot.fareRuleId(), snapshot.fareRuleSnapshot(), snapshot.fareCalculationVersion());
    }

    public record TripOrderSnapshot(
            String orderNo,
            Long plannedDistanceMeters,
            Long plannedDurationSeconds,
            String distanceSource,
            String routeMockVersion,
            BigDecimal estimatedAmount,
            Long fareRuleId,
            String fareRuleSnapshot,
            String fareCalculationVersion) {
    }
}
