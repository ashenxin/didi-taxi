package com.sx.order.model;

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
@TableName("order_outbox_event")
public class OrderOutboxEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String topic;
    private String eventType;
    private String aggregateId;
    /** JSON 字符串（MySQL JSON / H2 VARCHAR） */
    private String payload;
    /** PENDING/PROCESSING/PUBLISHED/FAILED */
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime processingAt;
    private String processingBy;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

