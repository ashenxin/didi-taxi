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
    private String couponName;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private String cityCode;
    private String productCode;
    private String status;
    private String lockedOrderNo;
    private LocalDateTime receivedAt;
    private LocalDateTime validStartAt;
    private LocalDateTime validEndAt;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
