package com.sx.passenger.lifecycle.domain;

/** 步骤在生命周期计划中的业务阶段。 */
public enum LifecycleStepPhase {
    /** 只读检查资金、订单等是否满足操作条件。 */
    PRECONDITION,
    /** 执行清理、迁移、冻结等主体动作。 */
    ACTION,
    /** 按合规要求保留或匿名化必须留存的数据。 */
    RETENTION,
    /** 主体提交后执行通知、投影同步、会话关闭等动作。 */
    POST_ACTION,
    /** 在本地事务中完成账号状态的最终提交。 */
    FINALIZE
}
