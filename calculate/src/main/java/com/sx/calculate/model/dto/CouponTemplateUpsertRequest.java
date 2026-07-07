package com.sx.calculate.model.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.sx.calculate.common.jackson.FlexibleLocalDateTimeDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class CouponTemplateUpsertRequest {
    @NotNull
    private Long companyId;
    private String companyNo;
    private String companyNameSnapshot;
    private String teamIdSnapshot;
    private String teamNameSnapshot;
    @NotBlank
    private String name;
    @NotBlank
    private String couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private BigDecimal maxDiscountAmount;
    @NotBlank
    private String cityCode;
    @NotBlank
    private String productCode;
    private Integer validDays;
    @NotNull
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime validStartAt;
    @NotNull
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime validEndAt;
    @NotNull
    private Integer totalCount;
    private Integer perUserLimit;
    private String issueType;
    private String sourceType;
    private String activityCode;
    private String ruleConfig;
    private Long operatorId;
}
