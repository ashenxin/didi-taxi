package com.sx.passenger.lifecycle.domain;

/** 单个生命周期 Step 的执行状态。 */
public enum LifecycleStepStatus {
    /** 等待编排器选择执行。 */
    PENDING,
    /** 已被当前执行器领取并正在处理。 */
    RUNNING,
    /** 参与者确认执行成功。 */
    SUCCEEDED,
    /** 业务条件不满足，等待阻断项解除。 */
    BLOCKED,
    /** 技术失败，等待下次自动重试。 */
    RETRY_PENDING,
    /** 自动恢复不可继续，等待人工处置。 */
    MANUAL_REVIEW,
    /** 根据运行时条件判定无需执行。 */
    SKIPPED,
    /** Operation 终止后，该未完成步骤被取消。 */
    CANCELLED
}
