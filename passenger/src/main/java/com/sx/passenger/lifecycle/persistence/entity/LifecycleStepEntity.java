package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 从计划定义固化出来的单个生命周期运行时步骤。 */
@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_step")
public class LifecycleStepEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long operationId;
    private String stepCode;
    private String participantCode;
    /** 计划阶段、执行方式、关键性和执行顺序。 */
    private String phase;
    private String executionMode;
    private String criticality;
    private Integer sequenceNo;
    private String status;
    /** 已执行次数及创建时固化的重试、超时参数。 */
    private Integer attemptCount;
    private Integer maxRetryCount;
    private Integer retryInitialSeconds;
    private Integer timeoutSeconds;
    /** 下一次可重试时间和当前运行超时边界。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRetryAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime timeoutAt;
    /** 本次命令与已应用结果事件 ID，用于消息关联和幂等。 */
    private String commandEventId;
    private String resultEventId;
    /** 最近失败的稳定错误码与诊断摘要。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorMessage;
    /** 计划配置、实际命令和参与者结果的脱敏 JSON 快照。 */
    private String stepConfig;
    private String commandSnapshot;
    private String resultSnapshot;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
