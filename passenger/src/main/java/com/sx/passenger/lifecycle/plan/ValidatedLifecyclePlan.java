package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

import java.util.List;

/**
 * 已通过全部结构和业务约束校验、可安全用于创建运行时快照的计划。
 *
 * @param code 计划代码
 * @param version 计划版本
 * @param operationType 已转换为强类型的操作类型
 * @param digest 原始计划规范化后计算的 SHA-256 摘要
 * @param steps 不可变的步骤定义列表
 */
public record ValidatedLifecyclePlan(
        String code,
        int version,
        LifecycleOperationType operationType,
        String digest,
        List<LifecycleStepDefinition> steps) {

    /** 防御性复制步骤列表，保证注册后的计划不能被调用方修改。 */
    public ValidatedLifecyclePlan {
        steps = List.copyOf(steps);
    }
}
