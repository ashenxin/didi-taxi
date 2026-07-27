package com.sx.passenger.lifecycle.plan;

/**
 * 带来源信息的原始计划。
 *
 * @param sourceName YAML 资源名，用于在校验错误中定位具体文件
 * @param definition 反序列化得到的计划内容
 */
public record LoadedLifecyclePlan(String sourceName, LifecyclePlanDefinition definition) {
}
