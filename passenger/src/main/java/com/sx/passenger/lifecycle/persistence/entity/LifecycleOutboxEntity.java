package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 与业务事务同库写入、由后台发布器可靠发送 Kafka 的生命周期发件箱记录。 */
@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_outbox")
public class LifecycleOutboxEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String eventId;
    private Long operationId;
    /** 聚合类型与业务标识。 */
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    /** 引发消息的审计事件和原始调用链。 */
    private String causationEventId;
    private String traceId;
    private String targetDomain;
    private String topic;
    /** Kafka 分区键，用于保持同一账号消息顺序。 */
    private String partitionKey;
    private String payload;
    /** PENDING、PROCESSING、PUBLISHED 或 FAILED。 */
    private String status;
    private Integer retryCount;
    private Integer maxRetryCount;
    private LocalDateTime nextRetryAt;
    /** 当前领取时间和 worker，用于识别陈旧 PROCESSING。 */
    private LocalDateTime processingAt;
    private String processingBy;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
