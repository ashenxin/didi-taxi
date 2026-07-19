package com.sx.calculate.job;

import com.sx.calculate.service.BenefitReconciliationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BenefitReconciliationJobTest {

    @Test
    void executeDelegatesJsonParameterToReconciliationService() {
        BenefitReconciliationService service = mock(BenefitReconciliationService.class);
        String param = "{\"mode\":\"CUSTOMER\",\"customerId\":10001}";
        BenefitReconciliationService.ReconciliationSummary expected =
                new BenefitReconciliationService.ReconciliationSummary(
                        "run-1", "CUSTOMER", 1, 1, 0, 0, 0, 12, "SUCCESS");
        when(service.reconcile(param)).thenReturn(expected);
        BenefitReconciliationJob job = new BenefitReconciliationJob(service);

        BenefitReconciliationService.ReconciliationSummary actual = job.execute(param);

        assertThat(actual).isEqualTo(expected);
        verify(service).reconcile(param);
    }
}
