package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 生命周期状态变化和人工处置的数据库审计事件，只追加、不承担消息发布。 */
@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_event")
public class LifecycleEventEntity {
    @TableId(type = IdType.AUTO) private Long id;
    /** 全局事件 ID。 */
    private String eventId;
    /** 所属 Operation 主键和 customerId。 */
    private Long operationId;
    private Long customerId;
    private String eventType;
    /** 状态迁移前后值；非迁移事件可以为空。 */
    private String fromStatus;
    private String toStatus;
    /** 操作者类型、脱敏标识及稳定原因码。 */
    private String actorType;
    private String actorId;
    private String reasonCode;
    /** 贯穿入口、编排和参与者调用的链路标识。 */
    private String traceId;
    /** 事件发生时的脱敏载荷快照。 */
    private String payloadSnapshot;
    private LocalDateTime createdAt;
}
