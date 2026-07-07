package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CouponLockResult {
    private Long couponId;
    private Long templateId;
    private Long companyId;
    private String companyNo;
    private String companyNameSnapshot;
    private String teamIdSnapshot;
    private String teamNameSnapshot;
    private String couponType;
    private String couponRuleSnapshot;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;
}
