package com.sx.passenger.lifecycle.plan;

/**
 * 步骤的自动重试配置。
 *
 * @param maxAttempts 最大执行尝试次数；达到上限后转人工处置
 * @param initialIntervalSeconds 第一次重试的等待秒数，后续由重试策略计算退避
 */
public record LifecycleRetryDefinition(Integer maxAttempts, Integer initialIntervalSeconds) {
}
