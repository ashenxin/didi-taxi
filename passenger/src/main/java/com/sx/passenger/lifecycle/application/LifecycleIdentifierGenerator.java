package com.sx.passenger.lifecycle.application;

/** 生命周期业务编号生成器，隔离具体 UUID 或序列实现。 */
public interface LifecycleIdentifierGenerator {
    /** 生成对外稳定的 Operation 业务编号。 */
    String nextOperationNo();
    /** 生成审计事件或 Outbox 事件 ID。 */
    String nextEventId();
}
