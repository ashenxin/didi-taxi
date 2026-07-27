package com.sx.passenger.lifecycle.domain;

/** 生命周期步骤失败后对整体流程的影响等级。 */
public enum LifecycleCriticality {
    /** 必须成功的关键步骤，失败时阻止流程正常完成。 */
    REQUIRED,
    /** 主体动作完成后执行的后置步骤，可按计划采用独立处置策略。 */
    POST_ACTION,
    /** 自动执行失败后必须转人工确认，不能无限自动重试。 */
    MANUAL_IF_FAILED
}
