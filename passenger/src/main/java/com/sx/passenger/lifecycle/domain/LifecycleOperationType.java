package com.sx.passenger.lifecycle.domain;

/** 生命周期平台当前支持的账号级操作类型。 */
public enum LifecycleOperationType {
    /** 更换当前账号绑定的手机号，但保持 customerId 与资产归属不变。 */
    PHONE_CHANGE,
    /** 注销账号，协调订单、钱包、权益、会话等参与者完成清理。 */
    ACCOUNT_CANCEL
}
