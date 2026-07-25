package com.sx.wallet.lifecycle.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.wallet.dao.WalletAutoPayAgreementMapper;
import com.sx.wallet.dao.WalletPaymentOrderMapper;
import com.sx.wallet.lifecycle.dao.WalletAutoPayTerminationMapper;
import com.sx.wallet.lifecycle.dao.WalletLifecycleParticipantInboxMapper;
import com.sx.wallet.lifecycle.exception.WalletLifecycleCommandConflictException;
import com.sx.wallet.lifecycle.exception.WalletLifecycleParticipantUnavailableException;
import com.sx.wallet.lifecycle.exception.WalletLifecycleProjectionConflictException;
import com.sx.wallet.lifecycle.exception.WalletLifecycleBlockedException;
import com.sx.wallet.lifecycle.exception.WalletLifecycleUnknownException;
import com.sx.wallet.lifecycle.model.WalletAutoPayTermination;
import com.sx.wallet.lifecycle.model.WalletLifecycleBlocker;
import com.sx.wallet.lifecycle.model.WalletLifecycleCommand;
import com.sx.wallet.lifecycle.model.WalletLifecycleParticipantInbox;
import com.sx.wallet.lifecycle.model.WalletLifecycleParticipantResult;
import com.sx.wallet.lifecycle.model.WalletManualResolutionRequest;
import com.sx.wallet.lifecycle.metrics.WalletLifecycleMetrics;
import com.sx.wallet.model.WalletAutoPayAgreement;
import com.sx.wallet.model.WalletPaymentOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AccountLifecycleWalletParticipantService {
    public static final String FINAL_CHECK = "WALLET_FINAL_CHECK";
    public static final String CLOSE_AUTO_PAY = "WALLET_CLOSE_AUTO_PAY";
    private final WalletLifecycleParticipantInboxMapper inboxes;
    private final WalletLifecycleProjectionService projections;
    private final WalletAccountWriteFence fence;
    private final WalletPaymentOrderMapper payments;
    private final WalletAutoPayAgreementMapper agreements;
    private final WalletAutoPayTerminationMapper terminations;
    private final WalletLifecycleRequestHasher hasher;
    private final ObjectMapper objectMapper;
    private final boolean mockEnabled;
    private final WalletLifecycleMetrics metrics;

    public AccountLifecycleWalletParticipantService(
            WalletLifecycleParticipantInboxMapper inboxes,
            WalletLifecycleProjectionService projections,
            WalletAccountWriteFence fence,
            WalletPaymentOrderMapper payments,
            WalletAutoPayAgreementMapper agreements,
            WalletAutoPayTerminationMapper terminations,
            WalletLifecycleRequestHasher hasher,
            ObjectMapper objectMapper,
            @Value("${wallet.payment.mock.enabled:true}") boolean mockEnabled,
            WalletLifecycleMetrics metrics) {
        this.inboxes = inboxes;
        this.projections = projections;
        this.fence = fence;
        this.payments = payments;
        this.agreements = agreements;
        this.terminations = terminations;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.mockEnabled = mockEnabled;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public WalletLifecycleParticipantResult precheck(long customerId) {
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
        try {
            return riskDecision(payments.selectLifecycleRisks(customerId));
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    @Transactional
    public WalletLifecycleParticipantResult fence(WalletLifecycleCommand command) {
        validate(command, FINAL_CHECK);
        try { return execute(command); }
        catch (RuntimeException ex) { throw unavailable(ex); }
    }

    @Transactional
    public WalletLifecycleParticipantResult action(WalletLifecycleCommand command) {
        validate(command, CLOSE_AUTO_PAY);
        try { return execute(command); }
        catch (RuntimeException ex) { throw unavailable(ex); }
    }

    @Transactional(readOnly = true)
    public WalletLifecycleParticipantResult findResult(String operationNo, String stepCode) {
        validateKey(operationNo, stepCode);
        try {
            WalletLifecycleParticipantInbox inbox = inboxes.find(operationNo.trim(), stepCode.trim());
            metrics.query(stepCode.trim(), inbox == null ? "NOT_FOUND" : "FOUND");
            return inbox == null ? null : fromInbox(inbox);
        } catch (RuntimeException ex) {
            metrics.query(stepCode.trim(), "UNKNOWN");
            throw unavailable(ex);
        }
    }

    @Transactional
    public WalletLifecycleParticipantResult resolveManually(WalletManualResolutionRequest request) {
        if (!CLOSE_AUTO_PAY.equals(request.stepCode().trim())) {
            throw new IllegalArgumentException("只能人工确认免密解约步骤");
        }
        WalletAutoPayTermination termination = terminations.findForUpdate(
                request.operationNo().trim(), request.stepCode().trim(), request.agreementId());
        if (termination == null) throw new IllegalArgumentException("免密解约审计不存在");
        if (!"UNKNOWN".equals(termination.getStatus())
                && !"MANUAL_CONFIRMED".equals(termination.getStatus())) {
            throw new IllegalArgumentException("当前解约状态不允许人工确认");
        }
        LocalDateTime now = LocalDateTime.now();
        if ("UNKNOWN".equals(termination.getStatus())) {
            WalletAutoPayAgreement agreement = agreements.selectById(termination.getAgreementId());
            if (agreement == null || !Objects.equals(agreement.getPassengerId(),
                    termination.getCustomerId())) {
                throw new WalletLifecycleUnknownException("免密协议不存在或归属冲突");
            }
            closeAgreement(agreement, now);
            termination.setStatus("MANUAL_CONFIRMED")
                    .setManualActor(request.actor().trim())
                    .setManualReason(request.reason().trim())
                    .setManualEvidence(request.evidence().trim())
                    .setResolvedAt(now).setUpdatedAt(now);
            if (terminations.updateById(termination) != 1) {
                throw new WalletLifecycleUnknownException("人工解约审计更新失败");
            }
        }
        return refreshActionResult(request.operationNo().trim(), request.stepCode().trim());
    }

    private WalletLifecycleParticipantResult execute(WalletLifecycleCommand command) {
        String operationNo = command.operationNo().trim();
        String stepCode = command.stepCode().trim();
        String requestHash = hasher.hashCommand(command);
        WalletLifecycleParticipantInbox prior = inboxes.find(operationNo, stepCode);
        if (prior != null) return replay(prior, requestHash);
        LocalDateTime now = LocalDateTime.now();
        WalletLifecycleParticipantInbox inbox = new WalletLifecycleParticipantInbox()
                .setOperationNo(operationNo).setStepCode(stepCode)
                .setCustomerId(command.customerId())
                .setLifecycleVersion(command.lifecycleVersion()).setRequestHash(requestHash)
                .setStatus("PROCESSING").setDecision("UNKNOWN")
                .setBlockerSnapshot("[]").setResultSnapshot("{}")
                .setCreatedAt(now).setUpdatedAt(now);
        try {
            if (inboxes.insert(inbox) != 1) throw new IllegalStateException("Wallet命令占位失败");
        } catch (DuplicateKeyException ex) {
            WalletLifecycleParticipantInbox raced = inboxes.findForUpdate(operationNo, stepCode);
            if (raced == null) throw new WalletLifecycleUnknownException("并发命令结果未知", ex);
            return replay(raced, requestHash);
        }
        WalletLifecycleParticipantResult result = FINAL_CHECK.equals(stepCode)
                ? executeFinalCheck(command) : executeClose(command);
        store(inbox, result);
        metrics.participant(stepCode, result.decision());
        return result;
    }

    private WalletLifecycleParticipantResult executeFinalCheck(WalletLifecycleCommand command) {
        projections.applyUnderLock(command);
        return riskDecision(payments.selectLifecycleRisksForUpdate(command.customerId()));
    }

    private WalletLifecycleParticipantResult executeClose(WalletLifecycleCommand command) {
        fence.lockAndRequireCurrentCancellation(command.customerId(), command.operationNo());
        List<WalletAutoPayAgreement> open = agreements.selectOpenForUpdate(command.customerId());
        LocalDateTime now = LocalDateTime.now();
        int confirmed = 0;
        int unknown = 0;
        for (WalletAutoPayAgreement agreement : open) {
            WalletAutoPayTermination termination = new WalletAutoPayTermination()
                    .setOperationNo(command.operationNo().trim())
                    .setStepCode(CLOSE_AUTO_PAY).setCustomerId(command.customerId())
                    .setAgreementId(agreement.getId()).setChannel(agreement.getChannel())
                    .setAgreementNoSnapshot(agreement.getAgreementNo())
                    .setChannelRequestNo("TERM" + UUID.randomUUID().toString().replace("-", ""))
                    .setCreatedAt(now).setUpdatedAt(now);
            if (mockEnabled) {
                termination.setStatus("CONFIRMED")
                        .setChannelResponseSnapshot("{\"result\":\"MOCK_CONFIRMED\"}")
                        .setResolvedAt(now);
                closeAgreement(agreement, now);
                confirmed++;
            } else {
                termination.setStatus("UNKNOWN")
                        .setChannelResponseSnapshot("{\"result\":\"CHANNEL_NOT_CONNECTED\"}");
                unknown++;
            }
            terminations.insert(termination);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agreementCount", open.size());
        result.put("confirmedCount", confirmed);
        result.put("unknownCount", unknown);
        if (unknown > 0) {
            return new WalletLifecycleParticipantResult("UNKNOWN",
                    List.of(new WalletLifecycleBlocker("AUTO_PAY_TERMINATION_UNKNOWN",
                            "AUTO_PAY_AGREEMENT", command.operationNo().trim(),
                            "QUERY_OR_MANUAL_REVIEW")), result);
        }
        return pass(result);
    }

    private WalletLifecycleParticipantResult refreshActionResult(String operationNo, String stepCode) {
        List<WalletAutoPayTermination> rows = terminations.selectForUpdate(operationNo, stepCode);
        long unknown = rows.stream().filter(r -> "UNKNOWN".equals(r.getStatus())).count();
        Map<String, Object> result = Map.of("agreementCount", rows.size(),
                "confirmedCount", rows.size() - unknown, "unknownCount", unknown);
        WalletLifecycleParticipantResult refreshed = unknown == 0 ? pass(result)
                : new WalletLifecycleParticipantResult("UNKNOWN",
                List.of(new WalletLifecycleBlocker("AUTO_PAY_TERMINATION_UNKNOWN",
                        "AUTO_PAY_AGREEMENT", operationNo, "QUERY_OR_MANUAL_REVIEW")), result);
        WalletLifecycleParticipantInbox inbox = inboxes.findForUpdate(operationNo, stepCode);
        if (inbox == null) throw new WalletLifecycleUnknownException("参与者结果不存在");
        store(inbox, refreshed);
        return refreshed;
    }

    private WalletLifecycleParticipantResult riskDecision(List<WalletPaymentOrder> risks) {
        List<WalletLifecycleBlocker> blockers = new ArrayList<>();
        for (WalletPaymentOrder payment : risks) {
            boolean duplicate = "DUPLICATE_SUCCESS".equals(payment.getStatus());
            blockers.add(new WalletLifecycleBlocker(
                    duplicate ? "DUPLICATE_PAYMENT_SUCCESS" : "PAYMENT_IN_PROGRESS",
                    "PAYMENT", payment.getPaymentNo(),
                    duplicate ? "CONTACT_OPERATIONS" : "WAIT_OR_QUERY_PAYMENT"));
        }
        return blockers.isEmpty() ? pass(Map.of("paymentRiskCount", 0))
                : new WalletLifecycleParticipantResult("BLOCKED", blockers,
                Map.of("paymentRiskCount", blockers.size()));
    }

    private void closeAgreement(WalletAutoPayAgreement agreement, LocalDateTime now) {
        agreement.setAgreementStatus("CLOSED").setIsDefault(0)
                .setClosedAt(now).setUpdatedAt(now).setFailReason(null);
        if (agreements.updateById(agreement) != 1) {
            throw new WalletLifecycleUnknownException("免密协议关闭失败");
        }
    }

    private void store(WalletLifecycleParticipantInbox inbox,
                       WalletLifecycleParticipantResult result) {
        inbox.setStatus("COMPLETED").setDecision(result.decision())
                .setBlockerSnapshot(write(result.blockers()))
                .setResultSnapshot(write(result.result())).setUpdatedAt(LocalDateTime.now());
        if (inboxes.updateById(inbox) != 1) {
            throw new WalletLifecycleUnknownException("Wallet参与者结果写入失败");
        }
    }

    private WalletLifecycleParticipantResult replay(
            WalletLifecycleParticipantInbox prior, String hash) {
        if (!Objects.equals(prior.getRequestHash(), hash)) {
            throw new WalletLifecycleCommandConflictException(
                    "同一operationNo和stepCode不能用于不同Wallet命令");
        }
        if (!"COMPLETED".equals(prior.getStatus())) {
            throw new WalletLifecycleParticipantUnavailableException(
                    "Wallet参与者结果尚未完成", null);
        }
        return fromInbox(prior);
    }

    private WalletLifecycleParticipantResult fromInbox(WalletLifecycleParticipantInbox inbox) {
        try {
            List<WalletLifecycleBlocker> blockers = objectMapper.readValue(
                    inbox.getBlockerSnapshot(), new TypeReference<>() {});
            Map<String, Object> result = objectMapper.readValue(
                    inbox.getResultSnapshot(), new TypeReference<>() {});
            return new WalletLifecycleParticipantResult(inbox.getDecision(), blockers, result);
        } catch (JsonProcessingException | RuntimeException ex) {
            throw new WalletLifecycleParticipantUnavailableException("Wallet结果无法解析", ex);
        }
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) {
            throw new WalletLifecycleParticipantUnavailableException("Wallet结果无法序列化", ex);
        }
    }

    private static WalletLifecycleParticipantResult pass(Map<String, Object> result) {
        return new WalletLifecycleParticipantResult("PASS", List.of(), result);
    }

    private static void validate(WalletLifecycleCommand command, String expectedStep) {
        if (!expectedStep.equals(command.stepCode().trim())) {
            throw new IllegalArgumentException("Wallet参与者不支持该stepCode");
        }
        if (!"CANCELLING".equals(command.targetLifecycleStatus().trim())) {
            throw new IllegalArgumentException("Wallet参与者只接受CANCELLING目标状态");
        }
    }

    private static void validateKey(String operationNo, String stepCode) {
        if (operationNo == null || operationNo.isBlank()) throw new IllegalArgumentException("operationNo不能为空");
        if (stepCode == null || stepCode.isBlank()) throw new IllegalArgumentException("stepCode不能为空");
        String step = stepCode.trim();
        if (!FINAL_CHECK.equals(step) && !CLOSE_AUTO_PAY.equals(step)) {
            throw new IllegalArgumentException("未知Wallet生命周期stepCode");
        }
    }

    private static RuntimeException unavailable(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException
                || ex instanceof WalletLifecycleBlockedException
                || ex instanceof WalletLifecycleUnknownException
                || ex instanceof WalletLifecycleCommandConflictException
                || ex instanceof WalletLifecycleProjectionConflictException
                || ex instanceof WalletLifecycleParticipantUnavailableException) {
            return ex;
        }
        return new WalletLifecycleParticipantUnavailableException(
                "Wallet生命周期参与者暂时不可用", ex);
    }
}
