package com.sx.passengerapi.model.ordercore;

import java.math.BigDecimal;

public class CreateOrderPreflightResult {
    private String decision;
    private String orderNo;
    private String blockingSettlementStatus;
    private String blockingAction;
    private Long plannedDistanceMeters;
    private Long plannedDurationSeconds;
    private String distanceSource;
    private String routeMockVersion;
    private BigDecimal estimatedAmount;
    private Long fareRuleId;
    private String fareRuleSnapshot;
    private String fareCalculationVersion;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getBlockingSettlementStatus() { return blockingSettlementStatus; }
    public void setBlockingSettlementStatus(String blockingSettlementStatus) { this.blockingSettlementStatus = blockingSettlementStatus; }
    public String getBlockingAction() { return blockingAction; }
    public void setBlockingAction(String blockingAction) { this.blockingAction = blockingAction; }
    public Long getPlannedDistanceMeters() { return plannedDistanceMeters; }
    public void setPlannedDistanceMeters(Long plannedDistanceMeters) { this.plannedDistanceMeters = plannedDistanceMeters; }
    public Long getPlannedDurationSeconds() { return plannedDurationSeconds; }
    public void setPlannedDurationSeconds(Long plannedDurationSeconds) { this.plannedDurationSeconds = plannedDurationSeconds; }
    public String getDistanceSource() { return distanceSource; }
    public void setDistanceSource(String distanceSource) { this.distanceSource = distanceSource; }
    public String getRouteMockVersion() { return routeMockVersion; }
    public void setRouteMockVersion(String routeMockVersion) { this.routeMockVersion = routeMockVersion; }
    public BigDecimal getEstimatedAmount() { return estimatedAmount; }
    public void setEstimatedAmount(BigDecimal estimatedAmount) { this.estimatedAmount = estimatedAmount; }
    public Long getFareRuleId() { return fareRuleId; }
    public void setFareRuleId(Long fareRuleId) { this.fareRuleId = fareRuleId; }
    public String getFareRuleSnapshot() { return fareRuleSnapshot; }
    public void setFareRuleSnapshot(String fareRuleSnapshot) { this.fareRuleSnapshot = fareRuleSnapshot; }
    public String getFareCalculationVersion() { return fareCalculationVersion; }
    public void setFareCalculationVersion(String fareCalculationVersion) { this.fareCalculationVersion = fareCalculationVersion; }
}
