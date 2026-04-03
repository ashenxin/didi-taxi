package com.sx.calculate.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class FareRuleUpsertBody {
    @NotBlank(message = "省份编码不能为空")
    private String provinceCode;

    @NotBlank(message = "城市编码不能为空")
    private String cityCode;

    @NotBlank(message = "产品线编码不能为空")
    private String productCode;

    private String ruleName;

    @NotNull(message = "生效开始时间不能为空")
    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    @NotNull(message = "起步价不能为空")
    private BigDecimal baseFare;

    @NotNull(message = "起步含里程不能为空")
    private BigDecimal includedDistanceKm;

    @NotNull(message = "起步含时长不能为空")
    private Integer includedDurationMin;

    @NotNull(message = "超出后每公里单价不能为空")
    private BigDecimal perKmPrice;

    @NotNull(message = "超出后每分钟单价不能为空")
    private BigDecimal perMinutePrice;

    private BigDecimal minimumFare;

    private BigDecimal maximumFare;
}

