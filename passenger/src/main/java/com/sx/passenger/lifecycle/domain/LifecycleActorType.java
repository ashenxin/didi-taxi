package com.sx.passenger.lifecycle.domain;

/** 发起或推动生命周期状态变化的主体类型，用于审计事件归因。 */
public enum LifecycleActorType {
    /** 乘客本人通过公开应用入口发起。 */
    CUSTOMER,
    /** 定时任务、恢复任务等系统逻辑自动执行。 */
    SYSTEM,
    /** 后台管理员通过人工处置入口执行。 */
    ADMIN,
    /** 其他内部服务通过受信任的服务间调用执行。 */
    SERVICE
}
