package com.sx.order.schedule;

import com.sx.order.model.TripOrder;
import com.sx.order.service.TripOrderWriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待派单超时：{@code CREATED} 超过配置时长仍无指派则系统取消（不依赖乘客轮询）。
 */
@Component
@Slf4j
public class CreatedDispatchTimeoutScheduler {

    private final TripOrderWriteService tripOrderWriteService;

    @Value("${order.dispatch.wait-timeout-seconds:180}")
    private int waitTimeoutSeconds;

    public CreatedDispatchTimeoutScheduler(TripOrderWriteService tripOrderWriteService) {
        this.tripOrderWriteService = tripOrderWriteService;
    }

    @Scheduled(fixedDelayString = "${order.dispatch.timeout-scan-interval-ms:30000}")
    public void scanStaleCreatedOrders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusSeconds(Math.max(60, waitTimeoutSeconds));
        List<TripOrder> list = tripOrderWriteService.listCreatedOlderThan(deadline);
        if (list == null || list.isEmpty()) {
            return;
        }
        int n = 0;
        for (TripOrder o : list) {
            try {
                if (tripOrderWriteService.cancelCreatedDispatchTimeoutOne(o.getOrderNo(), now)) {
                    n++;
                }
            } catch (RuntimeException ex) {
                log.warn("待派单超时系统取消失败 orderNo={}: {}", o.getOrderNo(), ex.toString());
            }
        }
        if (n > 0) {
            log.info("待派单超时扫描：本轮系统取消 {} 笔 CREATED 订单（等待≥{}秒）", n, waitTimeoutSeconds);
        }
    }
}
