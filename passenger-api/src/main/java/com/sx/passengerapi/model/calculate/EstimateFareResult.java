package com.sx.passengerapi.model.calculate;

import java.math.BigDecimal;

public class EstimateFareResult {
    private Long ruleId;
    private BigDecimal estimatedAmount;
    private Long distanceMeters;
    private Long durationSeconds;
    private String fareRuleSnapshot;
    private String fareCalculationVersion;

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public BigDecimal getEstimatedAmount() {
        return estimatedAmount;
    }

    public void setEstimatedAmount(BigDecimal estimatedAmount) {
        this.estimatedAmount = estimatedAmount;
    }

    public Long getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(Long distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getFareRuleSnapshot() {
        return fareRuleSnapshot;
    }

    public void setFareRuleSnapshot(String fareRuleSnapshot) {
        this.fareRuleSnapshot = fareRuleSnapshot;
    }

    public String getFareCalculationVersion() {
        return fareCalculationVersion;
    }

    public void setFareCalculationVersion(String fareCalculationVersion) {
        this.fareCalculationVersion = fareCalculationVersion;
    }
}
