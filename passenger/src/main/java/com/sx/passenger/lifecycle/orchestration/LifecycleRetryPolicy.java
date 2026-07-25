package com.sx.passenger.lifecycle.orchestration;

import java.time.Duration;

public final class LifecycleRetryPolicy {
    private static final long MAX_SECONDS = 3600;

    private LifecycleRetryPolicy() {}

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
