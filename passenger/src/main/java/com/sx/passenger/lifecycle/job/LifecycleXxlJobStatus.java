package com.sx.passenger.lifecycle.job;

import com.xxl.job.core.context.XxlJobHelper;
import org.springframework.stereotype.Component;

@Component
public class LifecycleXxlJobStatus {
    public void log(String message) {
        XxlJobHelper.log(message);
    }

    public void log(Throwable error) {
        XxlJobHelper.log(error);
    }

    public void fail(String message) {
        XxlJobHelper.handleFail(message);
    }
}
