package com.sx.passenger.lifecycle.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 一次换号或注销流程的聚合根及宏观执行状态。 */
@Getter @Setter @Accessors(chain = true)
@TableName("account_lifecycle_operation")
public class LifecycleOperationEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String operationNo;
    private Long customerId;
    private String operationType;
    private String status;
    /** 调用方幂等键及其请求内容 SHA-256。 */
    private String idempotencyKey;
    private String requestHash;
    /** 创建时预期和实际应用的 customer.lifecycle_version。 */
    private Long expectedLifecycleVersion;
    private Long appliedLifecycleVersion;
    /** 创建 Operation 时固化的计划身份。 */
    private String planCode;
    private Integer planVersion;
    private String planDigest;
    /** 为 1 表示已执行不可逆步骤，禁止再安全撤销。 */
    private Integer irreversibleStarted;
    /** 注销栅栏绑定的受限认证代次。 */
    private Long restrictedAuthEpoch;
    /** 当前未解决阻断数量和 Operation 乐观锁版本。 */
    private Integer activeBlockerCount;
    private Long rowVersion;
    /** RETRY_PENDING 下一次可被恢复任务唤醒的时间。 */
    private LocalDateTime nextWakeupAt;
    /** 最近失败摘要。 */
    private String lastErrorCode;
    private String lastErrorMessage;
    /** 创建请求的脱敏上下文。 */
    private String requestContext;
    private LocalDateTime requestedAt;
    private LocalDateTime fencedAt;
    private LocalDateTime executionStartedAt;
    private LocalDateTime completedAt;
    private LocalDateTime abortedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
