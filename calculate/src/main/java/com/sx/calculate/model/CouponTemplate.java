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
@TableName("coupon_template")
public class CouponTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
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
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer isDeleted;
}
