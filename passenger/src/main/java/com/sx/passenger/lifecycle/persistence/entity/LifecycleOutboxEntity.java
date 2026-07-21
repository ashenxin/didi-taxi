package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_outbox")
public class LifecycleOutboxEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String eventId;
    private Long operationId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String causationEventId;
    private String traceId;
    private String targetDomain;
    private String topic;
    private String partitionKey;
    private String payload;
    private String status;
    private Integer retryCount;
    private Integer maxRetryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime processingAt;
    private String processingBy;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
