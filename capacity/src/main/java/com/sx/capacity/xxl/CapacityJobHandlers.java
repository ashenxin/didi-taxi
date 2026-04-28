package com.sx.capacity.xxl;

import com.sx.capacity.service.LateDispatchMatchService;
import com.sx.capacity.service.OfferRescheduleService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CapacityJobHandlers {

    private final LateDispatchMatchService lateDispatchMatchService;
    private final OfferRescheduleService offerRescheduleService;

    /**
     * 迟滞匹配定时兜底：周期性扫描 CREATED 待派单，按 GEO 最近司机尝试指派。
     */
    @XxlJob("capacityLateDispatchScan")
    public void lateDispatchScan() {
        final long startMs = System.currentTimeMillis();
        XxlJobHelper.log("[START] job=capacityLateDispatchScan");
        int n = 0;
        try {
            n = lateDispatchMatchService.tryMatchScheduledScan();
            XxlJobHelper.log("late-dispatch matched {}", n);
            if (n > 0) {
                log.info("迟滞派单定时扫描：本轮成功匹配 {} 笔订单", n);
            }
        } finally {
            XxlJobHelper.log("[END] job=capacityLateDispatchScan matched={} elapsedMs={}", n, (System.currentTimeMillis() - startMs));
        }
    }

    /**
     * offer 超时打回后的改派 / 下一轮确认窗口。
     */
    @XxlJob("capacityOfferRescheduleScan")
    public void offerRescheduleScan() {
        final long startMs = System.currentTimeMillis();
        XxlJobHelper.log("[START] job=capacityOfferRescheduleScan");
        try {
            int n = offerRescheduleService.processRescheduleBatch();
            XxlJobHelper.log("offer-reschedule advanced {}", n);
            if (n > 0) {
                log.info("确认窗口改派扫描：本轮推进 {} 笔订单", n);
            }
            XxlJobHelper.log("[END] job=capacityOfferRescheduleScan advanced={} elapsedMs={}", n, (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("确认窗口改派扫描失败（可能 order-service 未启动/不可达）：{}", e.toString());
            XxlJobHelper.log("[END] job=capacityOfferRescheduleScan status=FAILED elapsedMs={} err={}", (System.currentTimeMillis() - startMs), e.toString());
            throw e;
        }
    }
}

