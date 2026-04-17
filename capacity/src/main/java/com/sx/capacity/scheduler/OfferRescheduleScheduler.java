package com.sx.capacity.scheduler;

import com.sx.capacity.service.OfferRescheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * offer 超时打回后的改派 / 下一轮确认窗口（与《司机端_上线听单与接单设计》§2.2、§3 扫描周期一致）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfferRescheduleScheduler {

    private final OfferRescheduleService offerRescheduleService;

    @Scheduled(fixedDelayString = "${capacity.dispatch.offer-reschedule.scan-interval-ms:5000}")
    public void scanAssignedAwaitingReschedule() {
        int n = offerRescheduleService.processRescheduleBatch();
        if (n > 0) {
            log.info("确认窗口改派扫描：本轮推进 {} 笔订单", n);
        }
    }
}
