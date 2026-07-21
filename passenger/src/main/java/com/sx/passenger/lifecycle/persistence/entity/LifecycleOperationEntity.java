package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_operation")
public class LifecycleOperationEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String operationNo;
    private Long customerId;
    private String operationType;
    private String status;
    private String idempotencyKey;
    private String requestHash;
    private Long expectedLifecycleVersion;
    private Long appliedLifecycleVersion;
    private String planCode;
    private Integer planVersion;
    private String planDigest;
    private Integer irreversibleStarted;
    private Long restrictedAuthEpoch;
    private Integer activeBlockerCount;
    private Long rowVersion;
    private LocalDateTime nextWakeupAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String requestContext;
    private LocalDateTime requestedAt;
    private LocalDateTime fencedAt;
    private LocalDateTime executionStartedAt;
    private LocalDateTime completedAt;
    private LocalDateTime abortedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
