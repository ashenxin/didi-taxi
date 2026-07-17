package com.sx.order.model.dto;

import java.math.BigDecimal;

public record CouponLockResult(Long couponId,
                               Long templateId,
                               Long companyId,
                               String companyNo,
                               String companyNameSnapshot,
                               String teamIdSnapshot,
                               String teamNameSnapshot,
                               String couponType,
                               String couponRuleSnapshot,
                               BigDecimal discountAmount,
                               BigDecimal payableAmount) {
}
