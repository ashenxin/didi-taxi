package com.sx.passenger.lifecycle.domain;

/**
 * 生命周期 Operation 或 Step 尝试执行非法状态迁移时抛出的领域异常。
 *
 * <p>异常表示调用方违反状态机约束，不应通过直接更新数据库状态绕过。
 */
public class InvalidLifecycleTransitionException extends IllegalStateException {

    /** @param message 包含对象类型、原状态、目标状态和拒绝原因的诊断信息 */
    public InvalidLifecycleTransitionException(String message) {
        super(message);
    }
}
