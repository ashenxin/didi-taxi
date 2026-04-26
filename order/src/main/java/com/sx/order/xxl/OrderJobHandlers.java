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
 * 将原本的 @Scheduled 任务迁移为 XXL-JOB Handler。
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
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusSeconds(Math.max(60, waitTimeoutSeconds));
        List<TripOrder> list = tripOrderWriteService.listCreatedOlderThan(deadline);
        if (list == null || list.isEmpty()) {
            XxlJobHelper.log("no stale CREATED orders (deadline={})", deadline);
            return;
        }
        int cancelled = 0;
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
    }

    /**
     * 派单确认窗口超时：将 PENDING_DRIVER_CONFIRM 打回 ASSIGNED（保留 driver_id）。
     */
    @XxlJob("orderOfferTimeoutScan")
    public void offerTimeoutScan() {
        int n = tripOrderWriteService.timeoutPendingDriverOffers(LocalDateTime.now());
        XxlJobHelper.log("timeout pending driver offers: {}", n);
        if (n > 0) {
            log.info("司机确认窗口超时扫描：本轮打回 {} 笔待确认订单", n);
        }
    }
}

