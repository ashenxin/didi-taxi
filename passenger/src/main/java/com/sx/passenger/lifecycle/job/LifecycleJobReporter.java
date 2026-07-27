package com.sx.passenger.lifecycle.job;

import com.sx.passenger.lifecycle.metrics.LifecycleOrchestrationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 生命周期 XXL-JOB 的统一执行包装器。
 *
 * <p>负责捕获顶层异常、记录标准批次指标和日志，并在存在技术失败或重试耗尽时
 * 显式调用 XXL-JOB 失败状态，避免任务异常却显示成功。
 */
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

    /** 执行任务主体并统一完成异常兜底和状态上报。 */
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
