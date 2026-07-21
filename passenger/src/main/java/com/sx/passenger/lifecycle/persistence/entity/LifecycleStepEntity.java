package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_step")
public class LifecycleStepEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long operationId;
    private String stepCode;
    private String participantCode;
    private String phase;
    private String executionMode;
    private String criticality;
    private Integer sequenceNo;
    private String status;
    private Integer attemptCount;
    private Integer maxRetryCount;
    private Integer retryInitialSeconds;
    private Integer timeoutSeconds;
    private LocalDateTime nextRetryAt;
    private LocalDateTime timeoutAt;
    private String commandEventId;
    private String resultEventId;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String stepConfig;
    private String commandSnapshot;
    private String resultSnapshot;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
