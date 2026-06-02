package com.sx.order.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 通知乘客聚合层刷新订单详情；只做 after-commit best-effort 通知，不参与订单状态裁决。
 */
@Component
@Slf4j
public class PassengerOrderChangedNotifier {

    private final RestClient restClient;

    public PassengerOrderChangedNotifier(
            RestClient.Builder restClientBuilder,
            @Value("${services.passenger-api.base-url:http://127.0.0.1:18080}") String passengerApiBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(passengerApiBaseUrl).build();
    }

    public void notifyAfterCommit(Long passengerId, String orderNo, String action) {
        if (passengerId == null || orderNo == null || orderNo.isBlank()) {
            return;
        }
        Runnable task = () -> notifyNow(passengerId, orderNo, action);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void notifyNow(Long passengerId, String orderNo, String action) {
        try {
            restClient.post()
                    .uri("/app/internal/v1/orders/changed")
                    .body(Map.of("passengerId", passengerId, "orderNo", orderNo))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("{}后通知乘客刷新订单失败 orderNo={} passengerId={} err={}",
                    action, orderNo, passengerId, e.toString());
        }
    }
}
