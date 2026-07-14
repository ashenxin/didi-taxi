package com.sx.calculate.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter
@Setter
@Accessors(chain = true)
@TableName("benefit_points_flow")
public class BenefitPointsFlow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private Long accountId;
    private String bizType;
    private String bizId;
    private Integer pointsDelta;
    private Integer balanceBefore;
    private Integer balanceAfter;
    private String flowDirection;
    private Long signRecordId;
    private String remark;
    private String ruleSnapshot;
    private String requestId;
    private LocalDateTime createdAt;
}
