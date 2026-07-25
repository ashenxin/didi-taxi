package com.sx.calculate.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.lifecycle.dao.CalculateLifecycleParticipantInboxMapper;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleCommandConflictException;
import com.sx.calculate.lifecycle.model.CalculateLifecycleCommand;
import com.sx.calculate.lifecycle.model.CalculateLifecycleDecision;
import com.sx.calculate.lifecycle.model.CalculateLifecycleParticipantInbox;
import com.sx.calculate.lifecycle.model.LockedCouponRisk;
import com.sx.calculate.lifecycle.service.AccountLifecycleCalculateParticipantService;
import com.sx.calculate.lifecycle.service.CalculateAccountWriteFence;
import com.sx.calculate.lifecycle.service.CalculateLifecycleProjectionService;
import com.sx.calculate.lifecycle.service.CalculateLifecycleRequestHasher;
import com.sx.calculate.lifecycle.metrics.CalculateLifecycleMetrics;
import com.sx.calculate.model.dto.BenefitClearPointsResult;
import com.sx.calculate.service.BenefitService;
import com.sx.calculate.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountLifecycleCalculateParticipantServiceTest {
    private final CalculateLifecycleParticipantInboxMapper inboxes =
            mock(CalculateLifecycleParticipantInboxMapper.class);
    private final CalculateLifecycleProjectionService projections =
            mock(CalculateLifecycleProjectionService.class);
    private final CalculateAccountWriteFence fence = mock(CalculateAccountWriteFence.class);
    private final CouponService coupons = mock(CouponService.class);
    private final BenefitService benefits = mock(BenefitService.class);
    private AccountLifecycleCalculateParticipantService service;

    @BeforeEach
    void setUp() {
        service = new AccountLifecycleCalculateParticipantService(inboxes, projections, fence,
                coupons, benefits, new CalculateLifecycleRequestHasher(), new ObjectMapper(),
                mock(CalculateLifecycleMetrics.class));
    }

    @Test
    void precheckReturnsStableLockedCouponBlocker() {
        when(coupons.inspectLockedCoupons(11L))
                .thenReturn(List.of(new LockedCouponRisk(7L, "ORDER-7")));

        var result = service.precheck(11L);

        assertThat(result.decision()).isEqualTo(CalculateLifecycleDecision.BLOCKED);
        assertThat(result.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.code()).isEqualTo("LOCKED_COUPON");
            assertThat(blocker.resourceNo()).isEqualTo("ORDER-7");
        });
    }

    @Test
    void finalCheckAppliesFenceAndStoresPermanentPassResult() {
        when(inboxes.insert(any(CalculateLifecycleParticipantInbox.class))).thenReturn(1);
        when(inboxes.updateById(any(CalculateLifecycleParticipantInbox.class))).thenReturn(1);
        when(coupons.inspectLockedCouponsForUpdate(12L)).thenReturn(List.of());

        var result = service.fence(command("op-12", "CALCULATE_FINAL_CHECK", 12L));

        assertThat(result.decision()).isEqualTo(CalculateLifecycleDecision.PASS);
        verify(projections).applyUnderLock(any());
        verify(coupons).inspectLockedCouponsForUpdate(12L);
        verify(inboxes).updateById(any(CalculateLifecycleParticipantInbox.class));
    }

    @Test
    void couponAndPointsActionsUseCurrentCancellationFence() {
        when(inboxes.insert(any(CalculateLifecycleParticipantInbox.class))).thenReturn(1);
        when(inboxes.updateById(any(CalculateLifecycleParticipantInbox.class))).thenReturn(1);
        when(coupons.invalidateByPassenger(13L, "ACCOUNT_CANCEL")).thenReturn(3);
        when(benefits.clearPointsForLifecycle(
                13L, "op-13", "CALCULATE_CLEAR_POINTS"))
                .thenReturn(new BenefitClearPointsResult(20, 20, 0, "CANCELLED", 9L));

        var couponResult = service.action(command(
                "op-13", "CALCULATE_INVALIDATE_UNUSED_COUPONS", 13L));
        var pointsResult = service.action(command(
                "op-13", "CALCULATE_CLEAR_POINTS", 13L));

        assertThat(couponResult.result()).containsEntry("invalidatedCount", 3);
        assertThat(pointsResult.result()).containsEntry("clearedPoints", 20);
        verify(fence).lockAndRequireCurrentCancellation(
                13L, "op-13", "CALCULATE_INVALIDATE_UNUSED_COUPONS");
        verify(fence).lockAndRequireCurrentCancellation(
                13L, "op-13", "CALCULATE_CLEAR_POINTS");
    }

    @Test
    void sameOperationAndStepWithDifferentPayloadConflicts() {
        CalculateLifecycleParticipantInbox prior = new CalculateLifecycleParticipantInbox()
                .setOperationNo("op-14")
                .setStepCode("CALCULATE_FINAL_CHECK")
                .setCustomerId(14L)
                .setRequestHash("different")
                .setStatus("COMPLETED")
                .setDecision("PASS")
                .setBlockerSnapshot("[]")
                .setResultSnapshot("{}")
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        when(inboxes.find("op-14", "CALCULATE_FINAL_CHECK")).thenReturn(prior);

        assertThatThrownBy(() -> service.fence(command(
                "op-14", "CALCULATE_FINAL_CHECK", 14L)))
                .isInstanceOf(CalculateLifecycleCommandConflictException.class);
    }

    private static CalculateLifecycleCommand command(
            String operationNo, String stepCode, long customerId) {
        return new CalculateLifecycleCommand(operationNo, stepCode, customerId, 1,
                "CANCELLING", "event-" + operationNo + "-" + stepCode,
                LocalDateTime.now());
    }
}
