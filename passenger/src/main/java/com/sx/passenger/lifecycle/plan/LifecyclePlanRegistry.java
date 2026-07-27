package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

/**
 * 生命周期计划只读注册表。
 *
 * <p>创建新 Operation 时读取当前生效计划；恢复已有 Operation 时按其固化的
 * code 和 version 读取历史计划，二者不能混用。
 */
public interface LifecyclePlanRegistry {
    /** 返回指定操作类型当前唯一的 ACTIVE 计划。 */
    ValidatedLifecyclePlan activePlan(LifecycleOperationType operationType);

    /** 返回指定代码和版本的计划，包括已标记为 INACTIVE 的历史版本。 */
    ValidatedLifecyclePlan get(String code, int version);
}
