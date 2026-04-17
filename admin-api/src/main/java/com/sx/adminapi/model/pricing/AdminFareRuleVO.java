package com.sx.adminapi.model.pricing;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.sx.adminapi.common.jackson.FlexibleLocalDateTimeDeserializer;
import com.sx.adminapi.common.jackson.LocalDateTimeDisplaySerializer;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class AdminFareRuleVO {
    private Long id;
    /** 运力公司主键 capacity.company.id */
    private Long companyId;
    /** 与 capacity.company.company_no 一致 */
    private String companyNo;
    /** 展示用，BFF 填充分公司名称 */
    private String companyName;
    private String provinceCode;
    private String cityCode;
    private String productCode;
    private String ruleName;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime effectiveFrom;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime effectiveTo;
    private BigDecimal baseFare;
    private BigDecimal includedDistanceKm;
    private Integer includedDurationMin;
    private BigDecimal perKmPrice;
    private BigDecimal perMinutePrice;
    private BigDecimal minimumFare;
    private BigDecimal maximumFare;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime createdAt;
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeDisplaySerializer.class)
    private LocalDateTime updatedAt;
}

