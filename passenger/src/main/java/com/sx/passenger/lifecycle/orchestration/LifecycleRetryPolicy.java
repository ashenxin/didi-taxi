package com.sx.passenger.lifecycle.orchestration;

import java.time.Duration;

/** 生命周期步骤的有上限指数退避策略。 */
public final class LifecycleRetryPolicy {
    private static final long MAX_SECONDS = 3600;

    private LifecycleRetryPolicy() {}

    /** 根据初始间隔和尝试次数计算延迟，最长不超过一小时。 */
    public static Duration delay(int initialSeconds, int attemptCount) {
        long initial = Math.max(1, initialSeconds);
        int exponent = Math.max(0, Math.min(attemptCount - 1, 20));
        long seconds;
        try {
            seconds = Math.multiplyExact(initial, 1L << exponent);
        } catch (ArithmeticException ex) {
            seconds = MAX_SECONDS;
        }
        return Duration.ofSeconds(Math.min(seconds, MAX_SECONDS));
    }
}
