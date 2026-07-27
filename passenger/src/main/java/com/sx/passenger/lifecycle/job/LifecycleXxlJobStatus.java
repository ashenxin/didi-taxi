package com.sx.passenger.lifecycle.job;

import com.xxl.job.core.context.XxlJobHelper;
import org.springframework.stereotype.Component;

/** 对 XXL-JOB 静态上下文 API 的薄封装，便于统一调用和单元测试替换。 */
@Component
public class LifecycleXxlJobStatus {
    /** 写入任务执行日志。 */
    public void log(String message) {
        XxlJobHelper.log(message);
    }

    public void log(Throwable error) {
        XxlJobHelper.log(error);
    }

    /** 将当前 XXL-JOB 执行结果标记为失败。 */
    public void fail(String message) {
        XxlJobHelper.handleFail(message);
    }
}
