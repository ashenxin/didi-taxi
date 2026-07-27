package com.sx.passenger.lifecycle.domain;

/**
 * 乘客账号本身的生命周期状态。
 *
 * <p>它描述 customer 当前能否作为正常账号使用，不等同于某一次
 * {@link LifecycleOperationStatus 生命周期操作的执行状态}。
 */
public enum AccountLifecycleStatus {
    /** 正常状态，可以登录并使用普通业务功能。 */
    ACTIVE,
    /** 注销栅栏已经建立，账号只允许完成受限的注销相关动作。 */
    CANCELLING,
    /** 注销已经最终完成，账号不再允许恢复为普通会话。 */
    CANCELLED
}
