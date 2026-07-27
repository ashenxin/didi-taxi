package com.sx.passenger.lifecycle.domain;

/** 一次生命周期 Operation 从受理到结束的宏观执行状态。 */
public enum LifecycleOperationStatus {
    /** 请求已经登记，尚未建立账号栅栏或开始执行。 */
    REQUESTED,
    /** 账号栅栏已建立，普通写入和普通会话已被限制。 */
    FENCED,
    /** 正在执行各参与者的前置条件检查。 */
    VALIDATING,
    /** 预检发现业务阻断项，需要条件消除后重新验证。 */
    BLOCKED,
    /** 正在执行实际清理、迁移或最终提交步骤。 */
    EXECUTING,
    /** 技术失败后等待达到下一次自动重试时间。 */
    RETRY_PENDING,
    /** 自动处理无法继续，需要管理员人工审查或恢复。 */
    MANUAL_REVIEW,
    /** 所有必要步骤均已完成，操作成功终结。 */
    COMPLETED,
    /** 在允许撤销的阶段主动终止，且未进入不可逆操作。 */
    ABORTED
}
