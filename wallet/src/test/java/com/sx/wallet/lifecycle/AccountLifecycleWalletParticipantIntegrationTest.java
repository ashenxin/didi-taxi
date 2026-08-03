package com.sx.wallet.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.wallet.dao.WalletAutoPayAgreementMapper;
import com.sx.wallet.dao.WalletPaymentOrderMapper;
import com.sx.wallet.lifecycle.dao.*;
import com.sx.wallet.lifecycle.exception.WalletLifecycleBlockedException;
import com.sx.wallet.lifecycle.model.WalletLifecycleCommand;
import com.sx.wallet.lifecycle.model.WalletManualResolutionRequest;
import com.sx.wallet.lifecycle.metrics.WalletLifecycleMetrics;
import com.sx.wallet.lifecycle.service.*;
import com.sx.wallet.model.WalletAutoPayAgreement;
import com.sx.wallet.model.WalletPaymentOrder;
import com.sx.wallet.model.dto.CreatePaymentAttemptRequest;
import com.sx.wallet.service.PaymentAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "wallet.lifecycle.write-fence.mode=ENFORCE")
class AccountLifecycleWalletParticipantIntegrationTest {
    @Autowired private AccountLifecycleWalletParticipantService participant;
    @Autowired private WalletLifecycleProjectionService projections;
    @Autowired private WalletLifecycleParticipantInboxMapper inboxes;
    @Autowired private WalletLifecycleProjectionMapper projectionMapper;
    @Autowired private WalletLifecycleEventInboxMapper events;
    @Autowired private WalletAutoPayTerminationMapper terminations;
    @Autowired private WalletPaymentOrderMapper payments;
    @Autowired private WalletAutoPayAgreementMapper agreements;
    @Autowired private WalletAccountWriteFence fence;
    @Autowired private WalletLifecycleRequestHasher hasher;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactions;
    @Autowired private PaymentAttemptService paymentAttempts;
    @Autowired private WalletLifecycleMetrics metrics;

    @BeforeEach
    void clean() {
        terminations.delete(null);
        inboxes.delete(null);
        projectionMapper.delete(null);
        events.delete(null);
        payments.delete(null);
        agreements.delete(null);
    }

    @Test
    void finalCheckReplaysSameEventAndRefreshesResolvedBlocker() {
        long customerId = 51001L;
        projections.seedActive(customerId, "seed-wallet-51001", LocalDateTime.now());
        WalletPaymentOrder payment = payment(
                customerId, "PAY-RISK-1", "CONFIRMING", "idem-risk");
        payments.insert(payment);

        var first = participant.fence(command("op-risk", "WALLET_FINAL_CHECK",
                customerId, "event-risk"));
        var replay = participant.fence(command("op-risk", "WALLET_FINAL_CHECK",
                customerId, "event-risk"));

        assertThat(first.decision()).isEqualTo("BLOCKED");
        assertThat(first.blockers()).singleElement()
                .satisfies(b -> assertThat(b.code()).isEqualTo("PAYMENT_IN_PROGRESS"));
        assertThat(replay).isEqualTo(first);
        assertThat(inboxes.selectCount(null)).isEqualTo(1);

        payment.setStatus("SUCCESS").setUpdatedAt(LocalDateTime.now());
        payments.updateById(payment);
        var refreshed = participant.fence(command("op-risk", "WALLET_FINAL_CHECK",
                customerId, "event-risk-recheck"));

        assertThat(refreshed.decision()).isEqualTo("PASS");
        assertThat(refreshed.blockers()).isEmpty();
        assertThat(inboxes.selectCount(null)).isEqualTo(1);
        assertThat(projectionMapper.selectById(customerId).getLifecycleVersion()).isEqualTo(1L);
    }

    @Test
    void closeActionIsIdempotentAndUnknownRequiresAuditedManualConfirmation() {
        long customerId = 51002L;
        projections.seedActive(customerId, "seed-wallet-51002", LocalDateTime.now());
        participant.fence(command("op-close", "WALLET_FINAL_CHECK",
                customerId, "event-close"));
        WalletAutoPayAgreement agreement = agreement(customerId);
        agreements.insert(agreement);

        AccountLifecycleWalletParticipantService realChannelUnavailable =
                new AccountLifecycleWalletParticipantService(inboxes, projections, fence, payments,
                        agreements, terminations, hasher, objectMapper, false, metrics);
        var unknown = transactions.execute(status -> realChannelUnavailable.action(
                command("op-close", "WALLET_CLOSE_AUTO_PAY", customerId, "event-action")));

        assertThat(unknown).isNotNull();
        assertThat(unknown.decision()).isEqualTo("UNKNOWN");
        assertThat(agreements.selectById(agreement.getId()).getAgreementStatus()).isEqualTo("ACTIVE");
        assertThat(terminations.selectCount(null)).isEqualTo(1);

        var resolved = participant.resolveManually(new WalletManualResolutionRequest(
                "op-close", "WALLET_CLOSE_AUTO_PAY", agreement.getId(),
                "ops-100", "渠道后台已确认解约", "ticket-20260724-1"));
        var replay = participant.findResult("op-close", "WALLET_CLOSE_AUTO_PAY");

        assertThat(resolved.decision()).isEqualTo("PASS");
        assertThat(replay.decision()).isEqualTo("PASS");
        assertThat(((Number) replay.result().get("unknownCount")).longValue()).isZero();
        assertThat(((Number) replay.result().get("confirmedCount")).longValue()).isEqualTo(1);
        assertThat(agreements.selectById(agreement.getId()).getAgreementStatus()).isEqualTo("CLOSED");
        assertThat(terminations.selectById(
                terminations.selectList(null).getFirst().getId()).getManualActor())
                .isEqualTo("ops-100");
    }

    @Test
    void confirmedChannelCloseIsIdempotentAndDoesNotRepeatTermination() {
        long customerId = 51004L;
        projections.seedActive(customerId, "seed-wallet-51004", LocalDateTime.now());
        participant.fence(command("op-confirmed", "WALLET_FINAL_CHECK",
                customerId, "event-confirmed"));
        WalletAutoPayAgreement agreement = agreement(customerId).setAgreementNo("AGREEMENT-4");
        agreements.insert(agreement);

        var first = participant.action(command("op-confirmed", "WALLET_CLOSE_AUTO_PAY",
                customerId, "event-confirmed-action"));
        var replay = participant.action(command("op-confirmed", "WALLET_CLOSE_AUTO_PAY",
                customerId, "event-confirmed-action"));

        assertThat(first.decision()).isEqualTo("PASS");
        assertThat(replay.decision()).isEqualTo("PASS");
        assertThat(agreements.selectById(agreement.getId()).getAgreementStatus()).isEqualTo("CLOSED");
        assertThat(terminations.selectCount(null)).isEqualTo(1);
        assertThat(terminations.selectList(null).getFirst().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void finalPassAndConcurrentNewPaymentCannotBothCommit() throws Exception {
        long customerId = 51003L;
        projections.seedActive(customerId, "seed-wallet-51003", LocalDateTime.now());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var finalFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return participant.fence(command("op-race", "WALLET_FINAL_CHECK",
                        customerId, "event-race")).decision();
            });
            var paymentFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    paymentAttempts.create(paymentRequest(customerId));
                    return true;
                } catch (WalletLifecycleBlockedException ex) {
                    return false;
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String finalDecision = finalFuture.get(10, TimeUnit.SECONDS);
            boolean paymentCreated = paymentFuture.get(10, TimeUnit.SECONDS);
            assertThat("PASS".equals(finalDecision) && paymentCreated).isFalse();
            assertThat(paymentCreated ? finalDecision : "PASS")
                    .isEqualTo(paymentCreated ? "BLOCKED" : finalDecision);
        }
    }

    private static WalletLifecycleCommand command(
            String operationNo, String stepCode, long customerId, String eventId) {
        return new WalletLifecycleCommand(operationNo, stepCode, customerId, 1,
                "CANCELLING", eventId, LocalDateTime.now().withNano(0));
    }

    private static WalletPaymentOrder payment(
            long customerId, String paymentNo, String status, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        return new WalletPaymentOrder().setPaymentNo(paymentNo).setOrderNo("ORDER-" + paymentNo)
                .setPassengerId(customerId).setTriggerType("MANUAL").setAttemptNo(1)
                .setChannel("ALIPAY").setAmount(new BigDecimal("12.00")).setStatus(status)
                .setChannelRequestNo("REQ-" + paymentNo).setIdempotencyKey(idempotencyKey)
                .setNotifyPayload("{}").setNotifyStatus("NONE").setNotifyRetryCount(0)
                .setNotifyVersion(0).setCreatedAt(now).setUpdatedAt(now);
    }

    private static WalletAutoPayAgreement agreement(long customerId) {
        LocalDateTime now = LocalDateTime.now();
        return new WalletAutoPayAgreement().setPassengerId(customerId).setChannel("ALIPAY")
                .setAgreementNo("AGREEMENT-1").setAgreementStatus("ACTIVE").setIsDefault(1)
                .setRawRequest("{}").setRawResponse("{}").setCreatedAt(now)
                .setUpdatedAt(now).setIsDeleted(0);
    }

    private static CreatePaymentAttemptRequest paymentRequest(long customerId) {
        CreatePaymentAttemptRequest request = new CreatePaymentAttemptRequest();
        request.setOrderNo("ORDER-RACE");
        request.setPassengerId(customerId);
        request.setTriggerType("MANUAL");
        request.setChannel("ALIPAY");
        request.setAmount(new BigDecimal("18.00"));
        request.setIdempotencyKey("idem-race-51003");
        return request;
    }
}
