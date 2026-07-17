package com.sx.wallet.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sx.wallet.client.OrderSettlementClient;
import com.sx.wallet.config.MockPaymentProperties;
import com.sx.wallet.dao.WalletAutoPayAgreementMapper;
import com.sx.wallet.dao.WalletPaymentOrderMapper;
import com.sx.wallet.model.WalletAutoPayAgreement;
import com.sx.wallet.model.WalletPaymentOrder;
import com.sx.wallet.model.dto.CreatePaymentAttemptRequest;
import com.sx.wallet.model.dto.PaymentResultNotification;
import com.sx.wallet.job.PaymentResultNotificationJob;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PaymentResultNotificationTest {
    private final WalletAutoPayAgreementMapper agreementMapper = mock(WalletAutoPayAgreementMapper.class);
    private final WalletPaymentOrderMapper paymentMapper = mock(WalletPaymentOrderMapper.class);
    private final PaymentChannel channel = mock(PaymentChannel.class);
    private final OrderSettlementClient orderClient = mock(OrderSettlementClient.class);
    private PaymentAttemptService service;

    @BeforeEach
    void setUp() {
        reset(agreementMapper, paymentMapper, channel, orderClient);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "wallet-notify-test"),
                WalletPaymentOrder.class);
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(true);
        service = new PaymentAttemptService(agreementMapper, paymentMapper, channel, properties,
                10, "unit-test-checkout-secret");
        when(paymentMapper.updateById(any(WalletPaymentOrder.class))).thenReturn(1);
        when(paymentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(orderClient.notifyPaymentResult(any())).thenReturn("PAID");
    }

    @Test
    void persistedAutoPayResultQueuesReliableNotificationWithoutSynchronousCallback() {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, null, null);
        when(agreementMapper.selectOne(any(Wrapper.class))).thenReturn(new WalletAutoPayAgreement()
                .setId(7L).setPassengerId(10001L).setChannel("ALIPAY")
                .setAgreementStatus("ACTIVE").setIsDefault(1).setIsDeleted(0));
        when(channel.initiate(any())).thenReturn(
                new PaymentChannel.ChannelResult("SUCCESS", "MOCK-TRADE-1", null));

        service.create(request());

        ArgumentCaptor<WalletPaymentOrder> paymentCaptor =
                ArgumentCaptor.forClass(WalletPaymentOrder.class);
        verify(paymentMapper).insert(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getNotifyStatus()).isEqualTo("PENDING");
        verify(orderClient, never()).notifyPaymentResult(any());
    }

    @Test
    void notificationJobDeliversTrustedFieldsAndMarksDuplicateSuccess() {
        WalletPaymentOrder attempt = new WalletPaymentOrder()
                .setId(9L).setPaymentNo("PAY-9").setOrderNo("ORDER-1")
                .setPassengerId(10001L).setChannel("ALIPAY")
                .setAmount(new BigDecimal("30.00")).setStatus("SUCCESS")
                .setChannelTradeNo("MOCK-TRADE-1").setNotifyStatus("PENDING")
                .setNotifyRetryCount(0).setNotifyVersion(1)
                .setResolvedAt(java.time.LocalDateTime.now());
        when(paymentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(attempt));
        when(orderClient.notifyPaymentResult(any())).thenReturn("DUPLICATE_SUCCESS");
        PaymentResultNotificationJob job = new PaymentResultNotificationJob(
                paymentMapper, orderClient, 50);

        job.deliver();

        ArgumentCaptor<PaymentResultNotification> captor =
                ArgumentCaptor.forClass(PaymentResultNotification.class);
        verify(orderClient).notifyPaymentResult(captor.capture());
        PaymentResultNotification notification = captor.getValue();
        assertThat(notification.orderNo()).isEqualTo("ORDER-1");
        assertThat(notification.passengerId()).isEqualTo(10001L);
        assertThat(notification.amount()).isEqualByComparingTo("30.00");
        assertThat(notification.status()).isEqualTo("SUCCESS");
        assertThat(notification.channelTradeNo()).isEqualTo("MOCK-TRADE-1");
        assertThat(attempt.getStatus()).isEqualTo("DUPLICATE_SUCCESS");
        assertThat(attempt.getNotifyStatus()).isEqualTo("SENT");
    }

    @Test
    void staleNotificationSnapshotCannotMarkNewerPaymentResultAsSent() {
        WalletPaymentOrder staleConfirming = new WalletPaymentOrder()
                .setId(10L).setPaymentNo("PAY-10").setOrderNo("ORDER-1")
                .setPassengerId(10001L).setChannel("ALIPAY")
                .setAmount(new BigDecimal("30.00")).setStatus("CONFIRMING")
                .setNotifyStatus("PENDING").setNotifyRetryCount(0).setNotifyVersion(1);
        when(paymentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(staleConfirming));
        when(paymentMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        PaymentResultNotificationJob job = new PaymentResultNotificationJob(
                paymentMapper, orderClient, 50);

        job.deliver();

        verify(orderClient).notifyPaymentResult(any());
        assertThat(staleConfirming.getNotifyStatus()).isEqualTo("PENDING");
        assertThat(staleConfirming.getStatus()).isEqualTo("CONFIRMING");
    }

    private static CreatePaymentAttemptRequest request() {
        CreatePaymentAttemptRequest request = new CreatePaymentAttemptRequest();
        request.setOrderNo("ORDER-1");
        request.setPassengerId(10001L);
        request.setAmount(new BigDecimal("30.00"));
        request.setTriggerType("AUTO_PAY");
        request.setIdempotencyKey("idem-notify");
        return request;
    }
}
