package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 参与者预检发现的业务阻断项，如未完成订单、余额或支付风险。 */
@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_blocker")
public class LifecycleBlockerEntity {
    @TableId(type = IdType.AUTO) private Long id;
    /** 所属 Operation 和发现阻断的 Step 主键。 */
    private Long operationId;
    private Long stepId;
    /** 报告阻断项的业务域及域内幂等键。 */
    private String domainCode;
    private String blockerKey;
    /** 阻断原因和关联业务资源。 */
    private String blockerType;
    private String resourceType;
    private String resourceId;
    /** ACTIVE 或 RESOLVED。 */
    private String status;
    /** 解除建议和发现时的脱敏业务快照 JSON。 */
    private String resolutionActions;
    private String snapshotJson;
    /** 首次发现、最近确认和最终解决时间。 */
    private LocalDateTime detectedAt;
    private LocalDateTime lastConfirmedAt;
    private LocalDateTime resolvedAt;
    /** 阻断项被解决或人工关闭的原因。 */
    private String resolutionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
