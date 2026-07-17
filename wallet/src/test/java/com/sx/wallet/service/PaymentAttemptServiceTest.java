package com.sx.wallet.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sx.wallet.dao.WalletAutoPayAgreementMapper;
import com.sx.wallet.dao.WalletPaymentOrderMapper;
import com.sx.wallet.model.WalletAutoPayAgreement;
import com.sx.wallet.model.WalletPaymentOrder;
import com.sx.wallet.model.dto.CreatePaymentAttemptRequest;
import com.sx.wallet.model.dto.PaymentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentAttemptServiceTest {

    private final WalletAutoPayAgreementMapper agreementMapper = mock(WalletAutoPayAgreementMapper.class);
    private final WalletPaymentOrderMapper paymentMapper = mock(WalletPaymentOrderMapper.class);
    private final PaymentChannel paymentChannel = mock(PaymentChannel.class);
    private PaymentAttemptService service;

    @BeforeEach
    void setUp() {
        reset(agreementMapper, paymentMapper, paymentChannel);
        service = new PaymentAttemptService(
                agreementMapper, paymentMapper, paymentChannel, 10, "unit-test-checkout-secret");
        when(paymentMapper.updateById(any(WalletPaymentOrder.class))).thenReturn(1);
        when(paymentChannel.initiate(any())).thenReturn(
                new PaymentChannel.ChannelResult("SUCCESS", "MOCK-TRADE-1", null));
    }

    @Test
    void autoPayUsesDefaultAgreementChannel() {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, null, null);
        when(agreementMapper.selectOne(any(Wrapper.class))).thenReturn(activeAgreement(7L, "ALIPAY"));

        PaymentResult result = service.create(request("AUTO_PAY", null, "idem-auto"));

        assertThat(result.getChannel()).isEqualTo("ALIPAY");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getAttemptNo()).isEqualTo(1);
        ArgumentCaptor<WalletPaymentOrder> captor = ArgumentCaptor.forClass(WalletPaymentOrder.class);
        verify(paymentMapper).insert(captor.capture());
        assertThat(captor.getValue().getAgreementId()).isEqualTo(7L);
        assertThat(captor.getValue().getTriggerType()).isEqualTo("AUTO_PAY");
    }

    @Test
    void manualPayRequiresExplicitSupportedChannelButNoAgreement() {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, null, null);

        PaymentResult result = service.create(request("MANUAL", "wechat", "idem-manual"));

        assertThat(result.getChannel()).isEqualTo("WECHAT");
        assertThat(result.getStatus()).isEqualTo("PAYING");
        assertThat(result.getCheckoutUrl()).contains(result.getPaymentNo());
        verify(agreementMapper, never()).selectOne(any(Wrapper.class));
        verify(paymentChannel, never()).initiate(any());
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalAttemptWithoutCallingChannel() {
        WalletPaymentOrder existing = attempt(1, "FAILED", "ALIPAY")
                .setTriggerType("AUTO_PAY").setIdempotencyKey("idem-same");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        PaymentResult result = service.create(request("AUTO_PAY", null, "idem-same"));

        assertThat(result.getPaymentNo()).isEqualTo(existing.getPaymentNo());
        verify(paymentChannel, never()).initiate(any());
        verify(paymentMapper, never()).insert(any(WalletPaymentOrder.class));
    }

    @Test
    void idempotencyKeyCannotBeReusedAcrossOrderPassengerOrAmount() {
        WalletPaymentOrder existing = attempt(1, "FAILED", "ALIPAY")
                .setTriggerType("AUTO_PAY")
                .setIdempotencyKey("idem-conflict");

        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        CreatePaymentAttemptRequest otherOrder = request("AUTO_PAY", null, "idem-conflict");
        otherOrder.setOrderNo("ORDER-2");
        assertThatThrownBy(() -> service.create(otherOrder)).hasMessageContaining("幂等键冲突");

        reset(paymentMapper);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        CreatePaymentAttemptRequest otherPassenger = request("AUTO_PAY", null, "idem-conflict");
        otherPassenger.setPassengerId(20002L);
        assertThatThrownBy(() -> service.create(otherPassenger)).hasMessageContaining("幂等键冲突");

        reset(paymentMapper);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        CreatePaymentAttemptRequest otherAmount = request("AUTO_PAY", null, "idem-conflict");
        otherAmount.setAmount(new BigDecimal("31.00"));
        assertThatThrownBy(() -> service.create(otherAmount)).hasMessageContaining("幂等键冲突");
    }

    @Test
    void manualIdempotencyReplayReconstructsSameCheckoutUrlWithoutPlaintextStorage() {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, null, null);
        PaymentResult first = service.create(request("MANUAL", "WECHAT", "idem-checkout"));
        ArgumentCaptor<WalletPaymentOrder> captor = ArgumentCaptor.forClass(WalletPaymentOrder.class);
        verify(paymentMapper).insert(captor.capture());
        WalletPaymentOrder stored = captor.getValue();

        reset(paymentMapper);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(stored);
        PaymentResult replay = service.create(request("MANUAL", "WECHAT", "idem-checkout"));

        assertThat(stored.getCheckoutTokenHash()).hasSize(64);
        assertThat(first.getCheckoutUrl()).isEqualTo(replay.getCheckoutUrl());
    }

    @Test
    void failedAttemptAllowsSwitchingChannelAndIncrementsAttemptNo() {
        WalletPaymentOrder failed = attempt(1, "FAILED", "ALIPAY");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, null, failed);

        PaymentResult result = service.create(request("MANUAL", "WECHAT", "idem-retry"));

        assertThat(result.getAttemptNo()).isEqualTo(2);
        assertThat(result.getChannel()).isEqualTo("WECHAT");
    }

    @Test
    void activeAttemptBlocksNewAttemptAndSuccessReturnsWithoutChannelCall() {
        WalletPaymentOrder paying = attempt(1, "PAYING", "ALIPAY");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, paying);

        assertThatThrownBy(() -> service.create(request("MANUAL", "WECHAT", "idem-blocked")))
                .hasMessageContaining("支付处理中");
        verify(paymentChannel, never()).initiate(any());

        reset(paymentMapper, paymentChannel);
        WalletPaymentOrder success = attempt(2, "SUCCESS", "WECHAT");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, success);
        PaymentResult result = service.create(request("AUTO_PAY", null, "idem-after-success"));

        assertThat(result.getPaymentNo()).isEqualTo(success.getPaymentNo());
        verify(paymentChannel, never()).initiate(any());
    }

    @Test
    void attemptNumberConflictRechecksWinningActiveAttemptBeforeRetrying() {
        WalletPaymentOrder winning = attempt(1, "PAYING", "ALIPAY");
        when(paymentMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null, null, null, null, null, null, winning);
        when(paymentMapper.insert(any(WalletPaymentOrder.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_wallet_order_attempt"));

        assertThatThrownBy(() -> service.create(request("MANUAL", "WECHAT", "idem-race")))
                .hasMessageContaining("支付处理中");

        verify(paymentMapper).insert(any(WalletPaymentOrder.class));
        verify(paymentChannel, never()).initiate(any());
    }

    private static CreatePaymentAttemptRequest request(String triggerType, String channel, String idempotencyKey) {
        CreatePaymentAttemptRequest request = new CreatePaymentAttemptRequest();
        request.setOrderNo("ORDER-1");
        request.setPassengerId(10001L);
        request.setAmount(new BigDecimal("30.00"));
        request.setTriggerType(triggerType);
        request.setChannel(channel);
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private static WalletAutoPayAgreement activeAgreement(Long id, String channel) {
        return new WalletAutoPayAgreement().setId(id).setPassengerId(10001L).setChannel(channel)
                .setAgreementStatus("ACTIVE").setIsDefault(1).setIsDeleted(0);
    }

    private static WalletPaymentOrder attempt(int attemptNo, String status, String channel) {
        return new WalletPaymentOrder().setId((long) attemptNo).setPaymentNo("PAY-" + attemptNo)
                .setOrderNo("ORDER-1").setPassengerId(10001L).setAttemptNo(attemptNo)
                .setTriggerType("MANUAL").setChannel(channel).setAmount(new BigDecimal("30.00"))
                .setStatus(status).setIdempotencyKey("idem-" + attemptNo);
    }
}
