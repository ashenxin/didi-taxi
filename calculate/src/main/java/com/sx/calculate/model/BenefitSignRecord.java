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
@TableName("benefit_sign_record")
public class BenefitSignRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private LocalDate signDate;
    private String signYearMonth;
    private Integer dayOfMonth;
    private Integer bitmapOffset;
    private Integer continuousDays;
    private Integer rewardPoints;
    private String rewardRuleCode;
    private String rewardSnapshot;
    private Long pointsFlowId;
    private String sourceType;
    private String requestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
