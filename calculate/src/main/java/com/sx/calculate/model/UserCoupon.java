package com.sx.calculate.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Accessors(chain = true)
@TableName("user_coupon")
public class UserCoupon {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Long passengerId;
    private String claimIdentityType;
    private String claimIdentityHash;
    private Long companyId;
    private String companyNo;
    private String companyNameSnapshot;
    private String teamIdSnapshot;
    private String teamNameSnapshot;
    private String couponName;
    private String couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private BigDecimal maxDiscountAmount;
    private String cityCode;
    private String productCode;
    private String status;
    private String lockedOrderNo;
    private BigDecimal lockedFinalAmount;
    private BigDecimal lockedDiscountAmount;
    private LocalDateTime receivedAt;
    private LocalDateTime validStartAt;
    private LocalDateTime validEndAt;
    private LocalDateTime usedAt;
    private String invalidReason;
    private LocalDateTime invalidAt;
    private String ruleSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
