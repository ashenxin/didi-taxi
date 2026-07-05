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
    private BigDecimal couponDiscountAmount;
    private BigDecimal payableAmount;
    private String paymentNo;
    private Integer paymentStatus;
    private BigDecimal paidAmount;
    private LocalDateTime paidAt;
    private String settlementStatus;
    private LocalDateTime settledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
