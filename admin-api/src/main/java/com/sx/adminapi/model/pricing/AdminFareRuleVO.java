package com.sx.adminapi.model.pricing;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class AdminFareRuleVO {
    private Long id;
    private String provinceCode;
    private String cityCode;
    private String productCode;
    private String ruleName;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private BigDecimal baseFare;
    private BigDecimal includedDistanceKm;
    private Integer includedDurationMin;
    private BigDecimal perKmPrice;
    private BigDecimal perMinutePrice;
    private BigDecimal minimumFare;
    private BigDecimal maximumFare;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

