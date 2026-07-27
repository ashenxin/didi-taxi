package com.sx.passenger.lifecycle.plan;

/**
 * 生命周期计划无法加载、违反结构约束或无法按版本找到时抛出的异常。
 *
 * <p>计划是编排执行的安全边界，因此配置错误会阻止相关组件正常启动，
 * 而不是带着不完整计划继续运行。
 */
public class InvalidLifecyclePlanException extends IllegalStateException {
    /** 创建只包含诊断消息的计划异常。 */
    public InvalidLifecyclePlanException(String message) {
        super(message);
    }

    /** 创建保留底层读取或反序列化原因的计划异常。 */
    public InvalidLifecyclePlanException(String message, Throwable cause) {
        super(message, cause);
    }
}
