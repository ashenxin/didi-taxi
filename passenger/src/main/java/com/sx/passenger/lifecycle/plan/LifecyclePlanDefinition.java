package com.sx.passenger.lifecycle.plan;

import java.util.List;

/**
 * 从 YAML 配置反序列化得到的原始生命周期计划。
 *
 * @param schemaVersion 配置格式版本，当前固定为 1
 * @param code 稳定的计划代码
 * @param version 计划修订版本，递增发布
 * @param operationType 适用的生命周期操作类型
 * @param status ACTIVE 表示当前生效，INACTIVE 表示仅保留历史版本
 * @param description 面向维护人员的计划说明
 * @param steps 按 sequence 描述的参与者步骤集合
 */
public record LifecyclePlanDefinition(
        Integer schemaVersion,
        String code,
        Integer version,
        String operationType,
        String status,
        String description,
        List<LifecycleStepDefinition> steps) {
}
