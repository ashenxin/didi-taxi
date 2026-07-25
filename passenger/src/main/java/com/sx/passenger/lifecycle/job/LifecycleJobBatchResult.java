package com.sx.passenger.lifecycle.job;

public record LifecycleJobBatchResult(
        int scanned,
        int claimed,
        int succeeded,
        int failed,
        int exhausted,
        int skipped,
        long elapsedMs) {

    public LifecycleJobBatchResult {
        if (scanned < 0 || claimed < 0 || succeeded < 0 || failed < 0
                || exhausted < 0 || skipped < 0 || elapsedMs < 0) {
            throw new IllegalArgumentException("生命周期任务批次统计不能为负数");
        }
    }

    public static LifecycleJobBatchResult success(int succeeded, long elapsedMs) {
        return new LifecycleJobBatchResult(
                succeeded, succeeded, succeeded, 0, 0, 0, elapsedMs);
    }

    public static LifecycleJobBatchResult unexpectedFailure(long elapsedMs) {
        return new LifecycleJobBatchResult(0, 0, 0, 1, 0, 0, elapsedMs);
    }

    public LifecycleJobBatchResult merge(LifecycleJobBatchResult other) {
        if (other == null) return this;
        return new LifecycleJobBatchResult(
                scanned + other.scanned,
                claimed + other.claimed,
                succeeded + other.succeeded,
                failed + other.failed,
                exhausted + other.exhausted,
                skipped + other.skipped,
                elapsedMs + other.elapsedMs);
    }

    public boolean hasTechnicalFailure() {
        return failed > 0 || exhausted > 0;
    }

    public String summary() {
        return "scanned=" + scanned
                + ", claimed=" + claimed
                + ", succeeded=" + succeeded
                + ", failed=" + failed
                + ", exhausted=" + exhausted
                + ", skipped=" + skipped
                + ", elapsedMs=" + elapsedMs;
    }
}
