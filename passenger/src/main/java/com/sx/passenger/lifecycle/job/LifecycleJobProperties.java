package com.sx.passenger.lifecycle.job;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生命周期后台任务的批量、超时和恢复参数。
 *
 * <p>默认值保证单次执行时间小于 XXL-JOB 外层超时；所有数值必须为正数，
 * 防止错误配置导致空转、无限等待或全表扫描。
 */
@Component
@ConfigurationProperties(prefix = "passenger.account-lifecycle.jobs")
public class LifecycleJobProperties {
    private final Outbox outbox = new Outbox();
    private final Recovery recovery = new Recovery();

    public Outbox getOutbox() {
        return outbox;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    @PostConstruct
    void validate() {
        requirePositive("outbox.batch-size", outbox.batchSize);
        requirePositive("outbox.send-timeout-seconds", outbox.sendTimeoutSeconds);
        requirePositive("outbox.batch-deadline-seconds", outbox.batchDeadlineSeconds);
        requirePositive("outbox.stale-processing-seconds", outbox.staleProcessingSeconds);
        requirePositive("recovery.operation-batch-size", recovery.operationBatchSize);
        requirePositive("recovery.step-batch-size", recovery.stepBatchSize);
        requirePositive("recovery.batch-deadline-seconds", recovery.batchDeadlineSeconds);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalStateException(
                    "passenger.account-lifecycle.jobs." + name + "必须为正数");
        }
    }

    /** Outbox 扫描、Kafka 发送和陈旧领取回收参数。 */
    public static class Outbox {
        private int batchSize = 50;
        private int sendTimeoutSeconds = 5;
        private int batchDeadlineSeconds = 25;
        private int staleProcessingSeconds = 120;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getSendTimeoutSeconds() {
            return sendTimeoutSeconds;
        }

        public void setSendTimeoutSeconds(int sendTimeoutSeconds) {
            this.sendTimeoutSeconds = sendTimeoutSeconds;
        }

        public int getBatchDeadlineSeconds() {
            return batchDeadlineSeconds;
        }

        public void setBatchDeadlineSeconds(int batchDeadlineSeconds) {
            this.batchDeadlineSeconds = batchDeadlineSeconds;
        }

        public int getStaleProcessingSeconds() {
            return staleProcessingSeconds;
        }

        public void setStaleProcessingSeconds(int staleProcessingSeconds) {
            this.staleProcessingSeconds = staleProcessingSeconds;
        }
    }

    /** Operation/Step 恢复批量和整批截止时间参数。 */
    public static class Recovery {
        private int operationBatchSize = 50;
        private int stepBatchSize = 50;
        private int batchDeadlineSeconds = 55;

        public int getOperationBatchSize() {
            return operationBatchSize;
        }

        public void setOperationBatchSize(int operationBatchSize) {
            this.operationBatchSize = operationBatchSize;
        }

        public int getStepBatchSize() {
            return stepBatchSize;
        }

        public void setStepBatchSize(int stepBatchSize) {
            this.stepBatchSize = stepBatchSize;
        }

        public int getBatchDeadlineSeconds() {
            return batchDeadlineSeconds;
        }

        public void setBatchDeadlineSeconds(int batchDeadlineSeconds) {
            this.batchDeadlineSeconds = batchDeadlineSeconds;
        }
    }
}
