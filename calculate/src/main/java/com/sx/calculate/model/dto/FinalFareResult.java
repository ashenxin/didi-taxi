package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class FinalFareResult {
    private BigDecimal finalAmount;
    private String fareCalculationVersion;
    private Long billingDistanceMeters;
    private Long billingDurationSeconds;
}
