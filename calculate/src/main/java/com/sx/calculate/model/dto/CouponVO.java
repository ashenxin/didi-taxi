package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class CouponVO {
    private Long couponId;
    private Long templateId;
    private Long companyId;
    private String companyNameSnapshot;
    private String couponName;
    private String couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private BigDecimal maxDiscountAmount;
    private BigDecimal actualDiscountAmount;
    private BigDecimal payableAmount;
    private String cityCode;
    private String productCode;
    private String status;
    private LocalDateTime validStartAt;
    private LocalDateTime validEndAt;
}
