package com.sx.calculate.lifecycle;

import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleProjectionMapper;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleBlockedException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleUnknownException;
import com.sx.calculate.lifecycle.model.CalculateAccountLifecycleProjection;
import com.sx.calculate.lifecycle.model.CalculateWriteAction;
import com.sx.calculate.lifecycle.service.CalculateAccountWriteFence;
import com.sx.calculate.lifecycle.service.CalculateLifecycleWriteFenceProperties;
import com.sx.calculate.lifecycle.metrics.CalculateLifecycleMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalculateAccountWriteFenceTest {

    private final CalculateAccountLifecycleProjectionMapper mapper =
            mock(CalculateAccountLifecycleProjectionMapper.class);

    @Test
    void enforceAllowsActiveAndBlocksAssetCreationWhileCancelling() {
        CalculateAccountWriteFence fence = fence("ENFORCE");
        when(mapper.selectForUpdate(1L)).thenReturn(projection("ACTIVE", 0, null));
        assertThatCode(() -> fence.lockAndRequireActive(1L, CalculateWriteAction.COUPON_CLAIM))
                .doesNotThrowAnyException();

        when(mapper.selectForUpdate(2L)).thenReturn(projection("CANCELLING", 0, "op-2"));
        assertThatThrownBy(() -> fence.lockAndRequireActive(2L, CalculateWriteAction.BENEFIT_SIGN_IN))
                .isInstanceOf(CalculateLifecycleBlockedException.class);
    }

    @Test
    void missingProjectionFailsClosedOnlyInEnforceMode() {
        assertThatThrownBy(() -> fence("ENFORCE")
                .lockAndRequireActive(3L, CalculateWriteAction.COUPON_LOCK))
                .isInstanceOf(CalculateLifecycleUnknownException.class);
        assertThatCode(() -> fence("SHADOW")
                .lockAndRequireActive(3L, CalculateWriteAction.COUPON_LOCK))
                .doesNotThrowAnyException();

        fence("OFF").lockAndRequireActive(4L, CalculateWriteAction.COUPON_LOCK);
        verify(mapper, never()).selectForUpdate(4L);
    }

    @Test
    void existingCouponResolutionIsAllowedForKnownLifecycleStates() {
        CalculateAccountWriteFence fence = fence("ENFORCE");
        when(mapper.selectForUpdate(5L)).thenReturn(projection("CANCELLING", 0, "op-5"));
        assertThatCode(() -> fence.lockAndRequireResolvable(5L, CalculateWriteAction.COUPON_RELEASE))
                .doesNotThrowAnyException();
    }

    @Test
    void cancellationActionRequiresMatchingOperationRegardlessOfMode() {
        when(mapper.selectForUpdate(6L)).thenReturn(projection("CANCELLING", 0, "op-6"));
        assertThatCode(() -> fence("OFF").lockAndRequireCurrentCancellation(
                6L, "op-6", "CALCULATE_CLEAR_POINTS")).doesNotThrowAnyException();
        assertThatThrownBy(() -> fence("SHADOW").lockAndRequireCurrentCancellation(
                6L, "other", "CALCULATE_CLEAR_POINTS"))
                .isInstanceOf(CalculateLifecycleBlockedException.class);
    }

    private CalculateAccountWriteFence fence(String mode) {
        CalculateLifecycleWriteFenceProperties properties =
                new CalculateLifecycleWriteFenceProperties();
        properties.setMode(mode);
        return new CalculateAccountWriteFence(mapper, properties,
                mock(CalculateLifecycleMetrics.class));
    }

    private static CalculateAccountLifecycleProjection projection(
            String status, int businessStatus, String operationNo) {
        return new CalculateAccountLifecycleProjection()
                .setCustomerId(1L)
                .setBusinessStatus(businessStatus)
                .setLifecycleStatus(status)
                .setLifecycleVersion(1L)
                .setOperationNo(operationNo)
                .setSourceEventId("event")
                .setRowVersion(0L);
    }
}
