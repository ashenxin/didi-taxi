package com.sx.passenger.lifecycle.job;

import com.sx.passenger.lifecycle.metrics.LifecycleOrchestrationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@Slf4j
public class LifecycleJobReporter {
    private final LifecycleOrchestrationMetrics metrics;
    private final LifecycleXxlJobStatus xxl;

    public LifecycleJobReporter(
            LifecycleOrchestrationMetrics metrics,
            LifecycleXxlJobStatus xxl) {
        this.metrics = metrics;
        this.xxl = xxl;
    }

    public void execute(String jobName, Supplier<LifecycleJobBatchResult> action) {
        long started = System.nanoTime();
        try {
            LifecycleJobBatchResult result = action.get();
            if (result == null) {
                throw new IllegalStateException("生命周期任务未返回批次摘要");
            }
            report(jobName, result);
        } catch (RuntimeException ex) {
            long elapsedMs = elapsedMs(started);
            LifecycleJobBatchResult result =
                    LifecycleJobBatchResult.unexpectedFailure(elapsedMs);
            metrics.recordJobResult(jobName, result);
            String message = "job=" + jobName + ", unexpectedFailure="
                    + ex.getClass().getSimpleName() + ", elapsedMs=" + elapsedMs;
            xxl.log(ex);
            xxl.fail(message);
            log.error("生命周期定时任务异常 {}", message, ex);
        }
    }

    private void report(String jobName, LifecycleJobBatchResult result) {
        metrics.recordJobResult(jobName, result);
        String message = "job=" + jobName + ", " + result.summary();
        xxl.log(message);
        if (result.hasTechnicalFailure()) {
            xxl.fail(message);
            log.warn("生命周期定时任务存在技术失败 {}", message);
        } else {
            log.info("生命周期定时任务完成 {}", message);
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
