package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_blocker")
public class LifecycleBlockerEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long operationId;
    private Long stepId;
    private String domainCode;
    private String blockerKey;
    private String blockerType;
    private String resourceType;
    private String resourceId;
    private String status;
    private String resolutionActions;
    private String snapshotJson;
    private LocalDateTime detectedAt;
    private LocalDateTime lastConfirmedAt;
    private LocalDateTime resolvedAt;
    private String resolutionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
