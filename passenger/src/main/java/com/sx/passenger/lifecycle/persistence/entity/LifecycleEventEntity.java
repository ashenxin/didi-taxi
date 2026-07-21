package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_event")
public class LifecycleEventEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String eventId;
    private Long operationId;
    private Long customerId;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String actorType;
    private String actorId;
    private String reasonCode;
    private String traceId;
    private String payloadSnapshot;
    private LocalDateTime createdAt;
}
