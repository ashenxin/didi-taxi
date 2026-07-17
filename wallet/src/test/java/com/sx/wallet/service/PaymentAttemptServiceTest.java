package com.sx.wallet.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sx.wallet.dao.WalletAutoPayAgreementMapper;
import com.sx.wallet.dao.WalletPaymentOrderMapper;
import com.sx.wallet.config.MockPaymentProperties;
import com.sx.wallet.model.WalletAutoPayAgreement;
import com.sx.wallet.model.WalletPaymentOrder;
import com.sx.wallet.model.dto.CreatePaymentAttemptRequest;
import com.sx.wallet.model.dto.PaymentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentAttemptServiceTest {

    private final WalletAutoPayAgreementMapper agreementMapper = mock(WalletAutoPayAgreementMapper.class);
    private final WalletPaymentOrderMapper paymentMapper = mock(WalletPaymentOrderMapper.class);
    private final PaymentChannel paymentChannel = mock(PaymentChannel.class);
    private final MockPaymentProperties mockProperties = new MockPaymentProperties();
    private PaymentAttemptService service;

    @BeforeEach
    void setUp() {
        reset(agreementMapper, paymentMapper, paymentChannel);
        mockProperties.setEnabled(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "wallet-payment-test"),
                WalletPaymentOrder.class);
        service = new PaymentAttemptService(
                agreementMapper, paymentMapper, paymentChannel, mockProperties,
                10, "unit-test-checkout-secret");
        when(paymentMapper.updateById(any(WalletPaymentOrder.class))).thenReturn(1);
        when(paymentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
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

    @Test
    void mockResolveValidatesTokenAndEnforcesStateTransitions() {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, null, null);
        PaymentResult created = service.create(request("MANUAL", "ALIPAY", "idem-resolve"));
        ArgumentCaptor<WalletPaymentOrder> captor = ArgumentCaptor.forClass(WalletPaymentOrder.class);
        verify(paymentMapper).insert(captor.capture());
        WalletPaymentOrder stored = captor.getValue();
        String token = created.getCheckoutUrl().substring(created.getCheckoutUrl().indexOf("token=") + 6);

        reset(paymentMapper);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(stored);
        when(paymentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        assertThatThrownBy(() -> service.resolveMockPayment(stored.getPaymentNo(), "wrong", "SUCCESS"))
                .hasMessageContaining("收银台不存在");

        PaymentResult confirming = service.resolveMockPayment(stored.getPaymentNo(), token, "CONFIRMING");
        assertThat(confirming.getStatus()).isEqualTo("CONFIRMING");
        PaymentResult confirmingReplay = service.resolveMockPayment(
                stored.getPaymentNo(), token, "CONFIRMING");
        assertThat(confirmingReplay.getStatus()).isEqualTo("CONFIRMING");
        assertThatThrownBy(() -> service.resolveMockPayment(stored.getPaymentNo(), token, "CANCELLED"))
                .hasMessageContaining("CONFIRMING只能");
        PaymentResult success = service.resolveMockPayment(stored.getPaymentNo(), token, "SUCCESS");
        assertThat(success.getStatus()).isEqualTo("SUCCESS");
        PaymentResult replay = service.resolveMockPayment(stored.getPaymentNo(), token, "SUCCESS");
        assertThat(replay.getStatus()).isEqualTo("SUCCESS");
        assertThatThrownBy(() -> service.resolveMockPayment(stored.getPaymentNo(), token, "FAILED"))
                .hasMessageContaining("终态冲突");
    }

    @Test
    void expiredCheckoutTokenIsRejected() {
        WalletPaymentOrder stored = attempt(1, "PAYING", "ALIPAY")
                .setTriggerType("MANUAL")
                .setCheckoutTokenHash("irrelevant")
                .setNotifyVersion(0)
                .setCheckoutTokenExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(stored);

        assertThatThrownBy(() -> service.resolveMockPayment(stored.getPaymentNo(), "token", "SUCCESS"))
                .hasMessageContaining("已过期");

        assertThat(stored.getStatus()).isEqualTo("CANCELLED");
        assertThat(stored.getFailedReason()).isEqualTo("CHECKOUT_TOKEN_EXPIRED");
        assertThat(stored.getNotifyStatus()).isEqualTo("PENDING");
        assertThat(stored.getNotifyVersion()).isEqualTo(1);
        assertThat(stored.getNextNotifyAt()).isNotNull();
        verify(paymentMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void paymentQueryExpiresPayingManualAttemptWithoutOpeningOldCashier() {
        WalletPaymentOrder stored = attempt(1, "PAYING", "ALIPAY")
                .setTriggerType("MANUAL")
                .setNotifyVersion(0)
                .setCheckoutTokenHash("irrelevant")
                .setCheckoutTokenExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(stored);

        PaymentResult result = service.getPaymentAttempt(stored.getPaymentNo());

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
        assertThat(stored.getNotifyStatus()).isEqualTo("PENDING");
        verify(paymentMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void paymentAttemptQueryDoesNotReissuePlaintextCheckoutToken() {
        WalletPaymentOrder stored = attempt(1, "PAYING", "ALIPAY")
                .setTriggerType("MANUAL")
                .setCheckoutTokenExpiresAt(java.time.LocalDateTime.now().plusMinutes(5))
                .setCheckoutTokenHash("stored-hash-only");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(stored);

        PaymentResult result = service.getPaymentAttempt(stored.getPaymentNo());

        assertThat(result.getCheckoutUrl()).isNull();
    }

    @Test
    void disabledMockRejectsManualAttemptBeforePersistence() {
        mockProperties.setEnabled(false);

        assertThatThrownBy(() -> service.create(request("MANUAL", "ALIPAY", "idem-disabled")))
                .hasMessageContaining("mock支付未启用");

        verify(paymentMapper, never()).insert(any(WalletPaymentOrder.class));
        verify(paymentMapper, never()).selectOne(any(Wrapper.class));
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
