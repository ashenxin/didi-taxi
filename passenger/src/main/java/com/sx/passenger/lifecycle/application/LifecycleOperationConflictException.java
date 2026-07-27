package com.sx.passenger.lifecycle.application;

/** 生命周期版本、幂等内容或数据库 CAS 冲突对应的领域异常。 */
public class LifecycleOperationConflictException extends IllegalStateException {
    public LifecycleOperationConflictException(String message) { super(message); }
}
