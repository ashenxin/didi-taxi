package com.sx.calculate.model.dto;

import com.sx.calculate.model.FareRule;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class FareRuleSnapshot {
    private Long ruleId;
    private BigDecimal baseFare;
    private BigDecimal includedDistanceKm;
    private Integer includedDurationMin;
    private BigDecimal perKmPrice;
    private BigDecimal perMinutePrice;
    private BigDecimal minimumFare;
    private BigDecimal maximumFare;

    public static FareRuleSnapshot from(FareRule rule) {
        FareRuleSnapshot snapshot = new FareRuleSnapshot();
        snapshot.setRuleId(rule.getId());
        snapshot.setBaseFare(rule.getBaseFare());
        snapshot.setIncludedDistanceKm(rule.getIncludedDistanceKm());
        snapshot.setIncludedDurationMin(rule.getIncludedDurationMin());
        snapshot.setPerKmPrice(rule.getPerKmPrice());
        snapshot.setPerMinutePrice(rule.getPerMinutePrice());
        snapshot.setMinimumFare(rule.getMinimumFare());
        snapshot.setMaximumFare(rule.getMaximumFare());
        return snapshot;
    }
}
