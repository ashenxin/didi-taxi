package com.sx.order.schedule;

import com.sx.order.service.TripOrderWriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 派单确认窗口超时：定时将 {@code PENDING_DRIVER_CONFIRM} 打回 {@code ASSIGNED}（保留 driver_id）。
 */
@Component
@Slf4j
public class OfferTimeoutScheduler {

    private final TripOrderWriteService tripOrderWriteService;

    public OfferTimeoutScheduler(TripOrderWriteService tripOrderWriteService) {
        this.tripOrderWriteService = tripOrderWriteService;
    }

    @Scheduled(fixedDelay = 5000)
    public void scanExpiredOffers() {
        int n = tripOrderWriteService.timeoutPendingDriverOffers(LocalDateTime.now());
        if (n > 0) {
            log.info("offer timeout scan: reverted {} pending driver confirm(s)", n);
        }
    }
}
