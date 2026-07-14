package com.sx.calculate.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Accessors(chain = true)
@TableName("benefit_points_account")
public class BenefitPointsAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private Integer availablePoints;
    private Integer totalEarnedPoints;
    private Integer totalUsedPoints;
    private Integer totalClearedPoints;
    private String status;
    private LocalDate lastSignDate;
    private Long lastPointsFlowId;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
