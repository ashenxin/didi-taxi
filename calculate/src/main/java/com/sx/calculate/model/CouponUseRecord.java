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
@TableName("coupon_use_record")
public class CouponUseRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userCouponId;
    private Long templateId;
    private Long passengerId;
    private String orderNo;
    private String actionType;
    private BigDecimal discountAmount;
    private String beforeStatus;
    private String afterStatus;
    private String reason;
    private String ruleSnapshot;
    private LocalDateTime createdAt;
}
