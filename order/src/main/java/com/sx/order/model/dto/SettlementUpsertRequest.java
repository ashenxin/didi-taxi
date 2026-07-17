package com.sx.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SettlementUpsertRequest {
    @NotBlank
    private String orderNo;
    @NotNull
    private Long passengerId;
    private BigDecimal estimatedAmount;
    @NotNull
    private BigDecimal finalAmount;
    private Long couponId;
    private Long couponTemplateId;
    private Long couponCompanyId;
    private String couponCompanyNo;
    private String couponCompanyNameSnapshot;
    private String couponTeamIdSnapshot;
    private String couponTeamNameSnapshot;
    private String couponType;
    @NotNull
    private BigDecimal couponDiscountAmount;
    private String couponRuleSnapshot;
    @NotNull
    private BigDecimal payableAmount;
    @NotNull
    private BigDecimal platformServiceFeeRate;
    @NotNull
    private BigDecimal platformServiceFeeAmount;
    @NotNull
    private BigDecimal carrierIncomeAmount;
    private String settlementSnapshot;
    private String settlementStatus;
}
