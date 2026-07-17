package com.sx.wallet.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.wallet.client.OrderSettlementClient;
import com.sx.wallet.dao.WalletPaymentOrderMapper;
import com.sx.wallet.model.WalletPaymentOrder;
import com.sx.wallet.model.dto.PaymentResultNotification;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class PaymentResultNotificationJob {
    private final WalletPaymentOrderMapper paymentMapper;
    private final OrderSettlementClient orderClient;
    private final int batchSize;

    public PaymentResultNotificationJob(WalletPaymentOrderMapper paymentMapper,
                                        OrderSettlementClient orderClient,
                                        @Value("${wallet.payment.notification-batch-size:50}") int batchSize) {
        this.paymentMapper = paymentMapper;
        this.orderClient = orderClient;
        this.batchSize = Math.max(1, batchSize);
    }

    @XxlJob("walletPaymentResultNotify")
    public void deliver() {
        LocalDateTime now = LocalDateTime.now();
        List<WalletPaymentOrder> attempts = paymentMapper.selectList(
                Wrappers.<WalletPaymentOrder>lambdaQuery()
                        .in(WalletPaymentOrder::getNotifyStatus, "PENDING", "FAILED")
                        .le(WalletPaymentOrder::getNextNotifyAt, now)
                        .orderByAsc(WalletPaymentOrder::getNextNotifyAt)
                        .last("LIMIT " + batchSize));
        for (WalletPaymentOrder attempt : attempts) {
            deliverOne(attempt);
        }
    }

    private void deliverOne(WalletPaymentOrder attempt) {
        try {
            String outcome = orderClient.notifyPaymentResult(new PaymentResultNotification(
                    attempt.getPaymentNo(), attempt.getOrderNo(), attempt.getPassengerId(),
                    attempt.getChannel(), attempt.getAmount(), attempt.getStatus(),
                    attempt.getChannelTradeNo(),
                    attempt.getResolvedAt() == null ? attempt.getUpdatedAt() : attempt.getResolvedAt()));
            LocalDateTime now = LocalDateTime.now();
            boolean duplicate = "DUPLICATE_SUCCESS".equals(outcome)
                    && "SUCCESS".equals(attempt.getStatus());
            var update = Wrappers.<WalletPaymentOrder>lambdaUpdate()
                    .set(WalletPaymentOrder::getNotifyStatus, "SENT")
                    .set(WalletPaymentOrder::getNotifiedAt, now)
                    .set(WalletPaymentOrder::getLastNotifyError, null)
                    .set(WalletPaymentOrder::getUpdatedAt, now)
                    .eq(WalletPaymentOrder::getId, attempt.getId())
                    .eq(WalletPaymentOrder::getNotifyVersion, attempt.getNotifyVersion())
                    .eq(WalletPaymentOrder::getStatus, attempt.getStatus())
                    .in(WalletPaymentOrder::getNotifyStatus, "PENDING", "FAILED");
            if (duplicate) {
                update.set(WalletPaymentOrder::getStatus, "DUPLICATE_SUCCESS");
            }
            if (paymentMapper.update(null, update) == 1) {
                attempt.setNotifyStatus("SENT").setNotifiedAt(now).setLastNotifyError(null);
                if (duplicate) {
                    attempt.setStatus("DUPLICATE_SUCCESS");
                }
            }
        } catch (RuntimeException ex) {
            int retryCount = attempt.getNotifyRetryCount() == null ? 1 : attempt.getNotifyRetryCount() + 1;
            long delaySeconds = Math.min(300L, 1L << Math.min(8, retryCount));
            paymentMapper.update(null, Wrappers.<WalletPaymentOrder>lambdaUpdate()
                    .set(WalletPaymentOrder::getNotifyStatus, "FAILED")
                    .set(WalletPaymentOrder::getNotifyRetryCount, retryCount)
                    .set(WalletPaymentOrder::getNextNotifyAt, LocalDateTime.now().plusSeconds(delaySeconds))
                    .set(WalletPaymentOrder::getLastNotifyError, abbreviate(ex.toString()))
                    .set(WalletPaymentOrder::getUpdatedAt, LocalDateTime.now())
                    .eq(WalletPaymentOrder::getId, attempt.getId())
                    .eq(WalletPaymentOrder::getNotifyVersion, attempt.getNotifyVersion())
                    .eq(WalletPaymentOrder::getStatus, attempt.getStatus())
                    .in(WalletPaymentOrder::getNotifyStatus, "PENDING", "FAILED"));
        }
    }

    private String abbreviate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
