package com.sx.calculate.lifecycle.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.lifecycle.dao.CalculateLifecycleParticipantInboxMapper;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleCommandConflictException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleParticipantUnavailableException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleProjectionConflictException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleBlockedException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleUnknownException;
import com.sx.calculate.lifecycle.model.CalculateLifecycleBlocker;
import com.sx.calculate.lifecycle.model.CalculateLifecycleCommand;
import com.sx.calculate.lifecycle.model.CalculateLifecycleDecision;
import com.sx.calculate.lifecycle.model.CalculateLifecycleParticipantInbox;
import com.sx.calculate.lifecycle.model.CalculateLifecycleParticipantResult;
import com.sx.calculate.lifecycle.model.LockedCouponRisk;
import com.sx.calculate.lifecycle.metrics.CalculateLifecycleMetrics;
import com.sx.calculate.model.dto.BenefitClearPointsResult;
import com.sx.calculate.service.BenefitService;
import com.sx.calculate.service.CouponService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AccountLifecycleCalculateParticipantService {
    public static final String FINAL_CHECK = "CALCULATE_FINAL_CHECK";
    public static final String INVALIDATE_UNUSED_COUPONS =
            "CALCULATE_INVALIDATE_UNUSED_COUPONS";
    public static final String CLEAR_POINTS = "CALCULATE_CLEAR_POINTS";
    private static final String COMPLETED = "COMPLETED";

    private final CalculateLifecycleParticipantInboxMapper inboxes;
    private final CalculateLifecycleProjectionService projections;
    private final CalculateAccountWriteFence fence;
    private final CouponService coupons;
    private final BenefitService benefits;
    private final CalculateLifecycleRequestHasher hasher;
    private final ObjectMapper objectMapper;
    private final CalculateLifecycleMetrics metrics;

    public AccountLifecycleCalculateParticipantService(
            CalculateLifecycleParticipantInboxMapper inboxes,
            CalculateLifecycleProjectionService projections,
            CalculateAccountWriteFence fence,
            CouponService coupons,
            BenefitService benefits,
            CalculateLifecycleRequestHasher hasher,
            ObjectMapper objectMapper,
            CalculateLifecycleMetrics metrics) {
        this.inboxes = inboxes;
        this.projections = projections;
        this.fence = fence;
        this.coupons = coupons;
        this.benefits = benefits;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public CalculateLifecycleParticipantResult precheck(long customerId) {
        if (customerId <= 0) throw new IllegalArgumentException("customerId必须为正数");
        try {
            return lockedCouponDecision(coupons.inspectLockedCoupons(customerId));
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    @Transactional
    public CalculateLifecycleParticipantResult fence(CalculateLifecycleCommand command) {
        validateTarget(command);
        if (!FINAL_CHECK.equals(command.stepCode().trim())) {
            throw new IllegalArgumentException("Calculate最终检查不支持该stepCode");
        }
        try {
            return execute(command);
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    @Transactional
    public CalculateLifecycleParticipantResult action(CalculateLifecycleCommand command) {
        validateTarget(command);
        String stepCode = command.stepCode().trim();
        if (!INVALIDATE_UNUSED_COUPONS.equals(stepCode) && !CLEAR_POINTS.equals(stepCode)) {
            throw new IllegalArgumentException("Calculate资产动作不支持该stepCode");
        }
        try {
            return execute(command);
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    @Transactional(readOnly = true)
    public CalculateLifecycleParticipantResult findResult(String operationNo, String stepCode) {
        validateResultKey(operationNo, stepCode);
        try {
            CalculateLifecycleParticipantInbox inbox = inboxes.find(operationNo.trim(), stepCode.trim());
            metrics.resultQuery(stepCode.trim(), inbox == null
                    ? CalculateLifecycleMetrics.QueryResult.NOT_FOUND
                    : CalculateLifecycleMetrics.QueryResult.FOUND);
            return inbox == null ? null : fromInbox(inbox);
        } catch (RuntimeException ex) {
            metrics.resultQuery(stepCode.trim(), CalculateLifecycleMetrics.QueryResult.UNKNOWN);
            throw unavailable(ex);
        }
    }

    private CalculateLifecycleParticipantResult execute(CalculateLifecycleCommand command) {
        String operationNo = command.operationNo().trim();
        String stepCode = command.stepCode().trim();
        String requestHash = hasher.hashCommand(command);
        CalculateLifecycleParticipantInbox prior = inboxes.findForUpdate(operationNo, stepCode);
        if (prior != null) return replayOrRefreshBlocked(prior, requestHash, command);

        LocalDateTime now = LocalDateTime.now();
        CalculateLifecycleParticipantInbox inbox = new CalculateLifecycleParticipantInbox()
                .setOperationNo(operationNo)
                .setStepCode(stepCode)
                .setCustomerId(command.customerId())
                .setLifecycleVersion(command.lifecycleVersion())
                .setRequestHash(requestHash)
                .setStatus("PROCESSING")
                .setDecision(CalculateLifecycleDecision.UNKNOWN.name())
                .setBlockerSnapshot("[]")
                .setResultSnapshot("{}")
                .setCreatedAt(now)
                .setUpdatedAt(now);
        try {
            if (inboxes.insert(inbox) != 1) {
                throw new IllegalStateException("Calculate生命周期参与者命令占位失败");
            }
        } catch (DuplicateKeyException ex) {
            CalculateLifecycleParticipantInbox raced = inboxes.findForUpdate(operationNo, stepCode);
            if (raced == null) {
                throw new IllegalStateException("Calculate生命周期参与者并发命令状态未知", ex);
            }
            return replayOrConflict(raced, requestHash);
        }

        CalculateLifecycleParticipantResult result = switch (stepCode) {
            case FINAL_CHECK -> executeFinalCheck(command);
            case INVALIDATE_UNUSED_COUPONS -> executeCouponInvalidation(command);
            case CLEAR_POINTS -> executePointsClear(command);
            default -> throw new IllegalArgumentException("未知的Calculate生命周期stepCode");
        };
        inbox.setStatus(COMPLETED)
                .setDecision(result.decision().name())
                .setBlockerSnapshot(write(result.blockers()))
                .setResultSnapshot(write(result.result()))
                .setUpdatedAt(LocalDateTime.now());
        if (inboxes.updateById(inbox) != 1) {
            throw new IllegalStateException("Calculate生命周期参与者结果写入失败");
        }
        metrics.participantCommand(stepCode,
                result.decision() == CalculateLifecycleDecision.BLOCKED
                        ? CalculateLifecycleMetrics.ParticipantDecision.BLOCKED
                        : CalculateLifecycleMetrics.ParticipantDecision.PASS);
        return result;
    }

    private CalculateLifecycleParticipantResult executeFinalCheck(
            CalculateLifecycleCommand command) {
        projections.applyUnderLock(command.toProjectionCommand());
        return lockedCouponDecision(coupons.inspectLockedCouponsForUpdate(command.customerId()));
    }

    private CalculateLifecycleParticipantResult executeCouponInvalidation(
            CalculateLifecycleCommand command) {
        fence.lockAndRequireCurrentCancellation(command.customerId(),
                command.operationNo().trim(), command.stepCode().trim());
        int count = coupons.invalidateByPassenger(command.customerId(), "ACCOUNT_CANCEL");
        return passed(Map.of("invalidatedCount", count));
    }

    private CalculateLifecycleParticipantResult executePointsClear(
            CalculateLifecycleCommand command) {
        fence.lockAndRequireCurrentCancellation(command.customerId(),
                command.operationNo().trim(), command.stepCode().trim());
        BenefitClearPointsResult cleared = benefits.clearPointsForLifecycle(
                command.customerId(), command.operationNo().trim(), command.stepCode().trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balanceBefore", cleared.balanceBefore());
        result.put("clearedPoints", cleared.clearedPoints());
        result.put("balanceAfter", cleared.balanceAfter());
        result.put("accountStatus", cleared.accountStatus());
        if (cleared.pointsFlowId() != null) result.put("pointsFlowId", cleared.pointsFlowId());
        return passed(result);
    }

    private static CalculateLifecycleParticipantResult lockedCouponDecision(
            List<LockedCouponRisk> risks) {
        List<CalculateLifecycleBlocker> blockers = risks.stream()
                .map(risk -> new CalculateLifecycleBlocker("LOCKED_COUPON", "COUPON",
                        risk.orderNo() == null || risk.orderNo().isBlank()
                                ? "COUPON-" + risk.couponId() : risk.orderNo(),
                        "COMPLETE_OR_CANCEL_ORDER"))
                .toList();
        Map<String, Object> result = Map.of("lockedCouponCount", blockers.size());
        return new CalculateLifecycleParticipantResult(
                blockers.isEmpty() ? CalculateLifecycleDecision.PASS
                        : CalculateLifecycleDecision.BLOCKED,
                blockers, result);
    }

    private static CalculateLifecycleParticipantResult passed(Map<String, Object> result) {
        return new CalculateLifecycleParticipantResult(
                CalculateLifecycleDecision.PASS, List.of(), result);
    }

    private CalculateLifecycleParticipantResult replayOrConflict(
            CalculateLifecycleParticipantInbox prior, String requestHash) {
        if (!Objects.equals(prior.getRequestHash(), requestHash)) {
            throw new CalculateLifecycleCommandConflictException(
                    "同一operationNo和stepCode不能用于不同Calculate生命周期命令");
        }
        if (!COMPLETED.equals(prior.getStatus())) {
            throw new IllegalStateException("Calculate生命周期参与者结果尚未完成");
        }
        return fromInbox(prior);
    }

    private CalculateLifecycleParticipantResult replayOrRefreshBlocked(
            CalculateLifecycleParticipantInbox prior, String requestHash,
            CalculateLifecycleCommand command) {
        if (Objects.equals(prior.getRequestHash(), requestHash)) {
            return replayOrConflict(prior, requestHash);
        }
        if (!FINAL_CHECK.equals(command.stepCode().trim())
                || !COMPLETED.equals(prior.getStatus())
                || !CalculateLifecycleDecision.BLOCKED.name().equals(prior.getDecision())
                || !Objects.equals(prior.getCustomerId(), command.customerId())
                || !Objects.equals(prior.getLifecycleVersion(), command.lifecycleVersion())) {
            throw new CalculateLifecycleCommandConflictException(
                    "同一operationNo和stepCode不能用于不同Calculate生命周期命令");
        }
        projections.requireCurrentTarget(command.toProjectionCommand());
        CalculateLifecycleParticipantResult refreshed = lockedCouponDecision(
                coupons.inspectLockedCouponsForUpdate(command.customerId()));
        prior.setRequestHash(requestHash)
                .setDecision(refreshed.decision().name())
                .setBlockerSnapshot(write(refreshed.blockers()))
                .setResultSnapshot(write(refreshed.result()))
                .setUpdatedAt(LocalDateTime.now());
        if (inboxes.updateById(prior) != 1) {
            throw new IllegalStateException("Calculate生命周期参与者重检结果写入失败");
        }
        metrics.participantCommand(command.stepCode().trim(),
                refreshed.decision() == CalculateLifecycleDecision.BLOCKED
                        ? CalculateLifecycleMetrics.ParticipantDecision.BLOCKED
                        : CalculateLifecycleMetrics.ParticipantDecision.PASS);
        return refreshed;
    }

    private CalculateLifecycleParticipantResult fromInbox(
            CalculateLifecycleParticipantInbox inbox) {
        try {
            List<CalculateLifecycleBlocker> blockers = objectMapper.readValue(
                    inbox.getBlockerSnapshot(), new TypeReference<>() {});
            Map<String, Object> result = objectMapper.readValue(
                    inbox.getResultSnapshot(), new TypeReference<>() {});
            return new CalculateLifecycleParticipantResult(
                    CalculateLifecycleDecision.valueOf(inbox.getDecision()), blockers, result);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new IllegalStateException("Calculate生命周期参与者结果无法解析", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Calculate生命周期参与者结果无法序列化", ex);
        }
    }

    private static void validateTarget(CalculateLifecycleCommand command) {
        if (!"CANCELLING".equals(command.targetLifecycleStatus().trim())) {
            throw new IllegalArgumentException("Calculate生命周期参与者只接受CANCELLING目标状态");
        }
    }

    private static void validateResultKey(String operationNo, String stepCode) {
        if (operationNo == null || operationNo.isBlank()) {
            throw new IllegalArgumentException("operationNo不能为空");
        }
        if (stepCode == null || stepCode.isBlank()) {
            throw new IllegalArgumentException("stepCode不能为空");
        }
        String normalized = stepCode.trim();
        if (!FINAL_CHECK.equals(normalized)
                && !INVALIDATE_UNUSED_COUPONS.equals(normalized)
                && !CLEAR_POINTS.equals(normalized)) {
            throw new IllegalArgumentException("未知的Calculate生命周期stepCode");
        }
    }

    private static RuntimeException unavailable(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException
                || ex instanceof CalculateLifecycleBlockedException
                || ex instanceof CalculateLifecycleUnknownException
                || ex instanceof CalculateLifecycleCommandConflictException
                || ex instanceof CalculateLifecycleProjectionConflictException
                || ex instanceof CalculateLifecycleParticipantUnavailableException) {
            return ex;
        }
        return new CalculateLifecycleParticipantUnavailableException(
                "Calculate生命周期参与者暂时不可用", ex);
    }
}
