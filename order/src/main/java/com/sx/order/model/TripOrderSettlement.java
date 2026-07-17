package com.sx.order.model;

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
@TableName("trip_order_settlement")
public class TripOrderSettlement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long passengerId;
    private BigDecimal estimatedAmount;
    private BigDecimal finalAmount;
    private Long couponId;
    private Long couponTemplateId;
    private Long couponCompanyId;
    private String couponCompanyNo;
    private String couponCompanyNameSnapshot;
    private String couponTeamIdSnapshot;
    private String couponTeamNameSnapshot;
    private String couponType;
    private BigDecimal couponDiscountAmount;
    private String couponRuleSnapshot;
    private BigDecimal payableAmount;
    private BigDecimal platformServiceFeeRate;
    private BigDecimal platformServiceFeeAmount;
    private BigDecimal carrierIncomeAmount;
    private String settlementSnapshot;
    private String paymentNo;
    private Integer paymentStatus;
    private BigDecimal paidAmount;
    private LocalDateTime paidAt;
    private String settlementStatus;
    private String failureCode;
    private String failureSummary;
    private Integer manualActionRequired;
    private Integer version;
    private LocalDateTime settledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
