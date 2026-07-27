package com.sx.passenger.lifecycle.plan;

/**
 * 生命周期计划中的一个步骤定义。
 *
 * @param code 全计划内唯一的步骤代码
 * @param participant 执行步骤的参与者代码
 * @param phase 步骤所属业务阶段
 * @param executionMode 同步检查、异步命令或本地事务
 * @param criticality 失败后对整体操作的影响等级
 * @param sequence 全计划执行顺序
 * @param timeoutSeconds 单次执行超时秒数
 * @param retry 自动重试配置
 */
public record LifecycleStepDefinition(
        String code,
        String participant,
        String phase,
        String executionMode,
        String criticality,
        Integer sequence,
        Integer timeoutSeconds,
        LifecycleRetryDefinition retry) {
}
