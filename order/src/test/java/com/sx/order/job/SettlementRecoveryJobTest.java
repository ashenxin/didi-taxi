package com.sx.order.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.service.TripOrderSettlementService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementRecoveryJobTest {

    @Test
    void retriesCalculatingButNeverRetriesFailedOrPaymentRequiredCharges() {
        TripOrderSettlementMapper mapper = mock(TripOrderSettlementMapper.class);
        TripOrderSettlementService service = mock(TripOrderSettlementService.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                settlement("T-CALCULATING", "CALCULATING", 0),
                settlement("T-MANUAL", "CALCULATING", 1)));
        SettlementRecoveryJob job = new SettlementRecoveryJob(mapper, service, 50);

        job.recover();

        verify(service).process("T-CALCULATING");
        verify(service, never()).process("T-MANUAL");
        verify(service, never()).process("T-PAYMENT-REQUIRED");
    }

    private static TripOrderSettlement settlement(String orderNo, String status, int manualActionRequired) {
        return new TripOrderSettlement().setOrderNo(orderNo).setSettlementStatus(status)
                .setManualActionRequired(manualActionRequired);
    }
}
