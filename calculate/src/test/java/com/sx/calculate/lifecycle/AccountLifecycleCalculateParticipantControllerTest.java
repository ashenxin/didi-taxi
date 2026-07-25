package com.sx.calculate.lifecycle;

import com.sx.calculate.lifecycle.controller.AccountLifecycleCalculateParticipantController;
import com.sx.calculate.lifecycle.model.CalculateLifecycleDecision;
import com.sx.calculate.lifecycle.model.CalculateLifecycleParticipantResult;
import com.sx.calculate.lifecycle.service.AccountLifecycleCalculateParticipantService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountLifecycleCalculateParticipantControllerTest {

    @Test
    void missingResultUsesStableNotFoundError() {
        AccountLifecycleCalculateParticipantService participant =
                mock(AccountLifecycleCalculateParticipantService.class);
        AccountLifecycleCalculateParticipantController controller =
                new AccountLifecycleCalculateParticipantController(participant);

        var response = controller.result("op-missing", "CALCULATE_FINAL_CHECK");

        assertThat(response.getCode()).isEqualTo(404);
        assertThat(response.getError()).isEqualTo("LIFECYCLE_RESULT_NOT_FOUND");
    }

    @Test
    void completedResultIsReturnedWithoutTransformation() {
        AccountLifecycleCalculateParticipantService participant =
                mock(AccountLifecycleCalculateParticipantService.class);
        AccountLifecycleCalculateParticipantController controller =
                new AccountLifecycleCalculateParticipantController(participant);
        CalculateLifecycleParticipantResult result = new CalculateLifecycleParticipantResult(
                CalculateLifecycleDecision.PASS, List.of(), Map.of("invalidatedCount", 2));
        when(participant.findResult("op-1", "CALCULATE_INVALIDATE_UNUSED_COUPONS"))
                .thenReturn(result);

        var response = controller.result("op-1", "CALCULATE_INVALIDATE_UNUSED_COUPONS");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isSameAs(result);
    }
}
