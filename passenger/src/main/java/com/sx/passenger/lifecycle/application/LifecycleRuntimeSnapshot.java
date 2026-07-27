package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;

import java.util.List;

/**
 * 一次新 Operation 需要原子落库的完整对象集合。
 *
 * @param operation 聚合根
 * @param steps 计划步骤快照
 * @param requestedEvent 初始审计事件
 * @param requestedOutbox 初始可靠消息
 */
public record LifecycleRuntimeSnapshot(LifecycleOperationEntity operation,
                                       List<LifecycleStepEntity> steps,
                                       LifecycleEventEntity requestedEvent,
                                       LifecycleOutboxEntity requestedOutbox) {
    /** 防御性复制步骤列表。 */
    public LifecycleRuntimeSnapshot {
        steps = List.copyOf(steps);
    }
}
