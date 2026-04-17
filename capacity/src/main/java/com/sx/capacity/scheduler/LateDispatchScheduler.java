package com.sx.capacity.scheduler;

import com.sx.capacity.service.LateDispatchMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 迟滞匹配定时兜底：周期性扫描 CREATED 待派单，按 GEO 最近司机尝试指派。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LateDispatchScheduler {

    private final LateDispatchMatchService lateDispatchMatchService;

    @Scheduled(fixedDelayString = "${capacity.dispatch.late-match-scan-interval-ms:30000}")
    public void scanPendingCreatedOrders() {
        int n = lateDispatchMatchService.tryMatchScheduledScan();
        if (n > 0) {
            log.info("迟滞派单定时扫描：本轮成功匹配 {} 笔订单", n);
        }
    }
}
