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
@TableName("benefit_reconciliation_issue")
public class BenefitReconciliationIssue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String issueKey;
    private String issueType;
    private String severity;
    private Long customerId;
    private LocalDate signDate;
    private String yearMonth;
    private String referenceType;
    private String referenceId;
    private String expectedSnapshot;
    private String actualSnapshot;
    private String status;
    private LocalDateTime firstDetectedAt;
    private LocalDateTime lastDetectedAt;
    private LocalDateTime resolvedAt;
    private Integer occurrenceCount;
    private String lastRunId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
