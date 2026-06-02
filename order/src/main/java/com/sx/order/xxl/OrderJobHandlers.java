package com.sx.order.xxl;

import com.sx.order.model.TripOrder;
import com.sx.order.service.TripOrderWriteService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单域 XXL-JOB Handler。
 */
@Component
@Slf4j
public class OrderJobHandlers {

    private final TripOrderWriteService tripOrderWriteService;

    @Value("${order.dispatch.wait-timeout-seconds:180}")
    private int waitTimeoutSeconds;

    public OrderJobHandlers(TripOrderWriteService tripOrderWriteService) {
        this.tripOrderWriteService = tripOrderWriteService;
    }

    /**
     * 待派单超时：CREATED 超过 waitTimeoutSeconds 仍无指派则系统取消。
     */
    @XxlJob("orderCreatedDispatchTimeoutScan")
    public void createdDispatchTimeoutScan() {
        final long startMs = System.currentTimeMillis();
        XxlJobHelper.log("[START] job=orderCreatedDispatchTimeoutScan waitTimeoutSeconds={}", waitTimeoutSeconds);
        int scanned = 0;
        int cancelled = 0;
        LocalDateTime deadline = null;
        try {
            LocalDateTime now = LocalDateTime.now();
            deadline = now.minusSeconds(Math.max(60, waitTimeoutSeconds));
            List<TripOrder> list = tripOrderWriteService.listCreatedOlderThan(deadline);
            if (list == null || list.isEmpty()) {
                XxlJobHelper.log("no stale CREATED orders (deadline={})", deadline);
                return;
            }
            scanned = list.size();
            for (TripOrder o : list) {
                if (o == null || o.getOrderNo() == null) {
                    continue;
                }
                try {
                    if (tripOrderWriteService.cancelCreatedDispatchTimeoutOne(o.getOrderNo(), now)) {
                        cancelled++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("待派单超时系统取消失败 orderNo={}: {}", o.getOrderNo(), ex.toString());
                }
            }
            XxlJobHelper.log("cancelled {} stale CREATED orders (waitTimeoutSeconds={})", cancelled, waitTimeoutSeconds);
        } finally {
            XxlJobHelper.log(
                    "[END] job=orderCreatedDispatchTimeoutScan scanned={} cancelled={} deadline={} elapsedMs={}",
                    scanned, cancelled, deadline, (System.currentTimeMillis() - startMs)
            );
        }
    }

    /**
     * 派单确认窗口超时：释放 PENDING_DRIVER_CONFIRM 的本轮司机指派，并退回 CREATED 重新派单。
     */
    @XxlJob("orderOfferTimeoutScan")
    public void offerTimeoutScan() {
        final long startMs = System.currentTimeMillis();
        XxlJobHelper.log("[START] job=orderOfferTimeoutScan");
        int n = 0;
        try {
            n = tripOrderWriteService.timeoutPendingDriverOffers(LocalDateTime.now());
            XxlJobHelper.log("timeout pending driver offers: {}", n);
            if (n > 0) {
                log.info("司机确认窗口超时扫描：本轮释放 {} 笔待确认指派", n);
            }
        } finally {
            XxlJobHelper.log("[END] job=orderOfferTimeoutScan affected={} elapsedMs={}", n, (System.currentTimeMillis() - startMs));
        }
    }
}
