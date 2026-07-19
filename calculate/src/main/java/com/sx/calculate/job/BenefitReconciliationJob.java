package com.sx.calculate.job;

import com.sx.calculate.service.BenefitReconciliationService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class BenefitReconciliationJob {
    private final BenefitReconciliationService reconciliationService;

    public BenefitReconciliationJob(BenefitReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @XxlJob("benefitSignReconciliation")
    public void run() {
        BenefitReconciliationService.ReconciliationSummary summary = execute(XxlJobHelper.getJobParam());
        String message = "runId=" + summary.runId()
                + ", mode=" + summary.mode()
                + ", scannedCustomerCount=" + summary.scannedCustomerCount()
                + ", bitmapRepairedCount=" + summary.bitmapRepairedCount()
                + ", issueFoundCount=" + summary.issueFoundCount()
                + ", repairFailedCount=" + summary.repairFailedCount()
                + ", failedCustomerCount=" + summary.failedCustomerCount()
                + ", durationMs=" + summary.durationMs()
                + ", status=" + summary.status();
        XxlJobHelper.log(message);
        log.info("福利签到 XXL-Job 执行摘要 {}", message);
        if (!"SUCCESS".equals(summary.status())) {
            XxlJobHelper.handleFail(message);
        }
    }

    public BenefitReconciliationService.ReconciliationSummary execute(String jobParam) {
        return reconciliationService.reconcile(jobParam);
    }
}
