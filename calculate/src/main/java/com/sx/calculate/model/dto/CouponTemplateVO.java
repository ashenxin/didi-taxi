package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class CouponTemplateVO {
    private Long id;
    private Long companyId;
    private String companyNo;
    private String companyNameSnapshot;
    private String teamIdSnapshot;
    private String teamNameSnapshot;
    private String name;
    private String couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private BigDecimal maxDiscountAmount;
    private String cityCode;
    private String productCode;
    private Integer validDays;
    private LocalDateTime validStartAt;
    private LocalDateTime validEndAt;
    private Integer totalCount;
    private Integer receivedCount;
    private Integer usedCount;
    private Integer perUserLimit;
    private String issueType;
    private String sourceType;
    private String activityCode;
    private String ruleConfig;
    private String status;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime offlineAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
