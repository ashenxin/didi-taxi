package com.sx.passenger.lifecycle.domain;

/** 生命周期步骤的调用和完成确认方式。 */
public enum LifecycleExecutionMode {
    /** 同步调用参与者做只读预检，并立即取得裁决结果。 */
    SYNC_CHECK,
    /** 通过消息发送命令，等待参与者异步返回结果。 */
    ASYNC_COMMAND,
    /** 在 passenger 本地数据库事务中执行的最终提交动作。 */
    LOCAL_TRANSACTION
}
