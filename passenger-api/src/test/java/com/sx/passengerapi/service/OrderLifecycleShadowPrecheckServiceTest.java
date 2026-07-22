package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.PassengerAuthMetrics;
import com.sx.passengerapi.client.OrderLifecycleClient;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.lifecycle.OrderLifecycleParticipantResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

class OrderLifecycleShadowPrecheckServiceTest {
    private final OrderLifecycleClient client = mock(OrderLifecycleClient.class);
    private final PassengerAuthMetrics metrics = mock(PassengerAuthMetrics.class);
    private final OrderLifecycleShadowPrecheckService service =
            new OrderLifecycleShadowPrecheckService(client, metrics);

    @ParameterizedTest
    @CsvSource({
            "PASS,PASS,MATCH",
            "ACTIVE_ORDER,BLOCKED,MATCH",
            "UNSETTLED_ORDER,BLOCKED,MATCH",
            "ACTIVE_ORDER,PASS,LEGACY_ONLY",
            "PASS,BLOCKED,NEW_ONLY"
    })
    void comparesLegacyAndNewOrderRiskWithoutChangingEitherDecision(
            LegacyOrderRiskDecision legacy, String current, String expected) {
        when(client.precheck(any())).thenReturn(ResponseVo.success(
                new OrderLifecycleParticipantResult(current, List.of())));

        var result = service.compare(7L, legacy);

        assertThat(result.name()).isEqualTo(expected);
        verify(metrics).orderShadow(PassengerAuthMetrics.OrderShadowResult.valueOf(expected));
    }

    @Test
    void timeoutInvalidResponseAndUnknownDecisionBecomeErrorOnly() {
        doThrow(new IllegalStateException("timeout")).when(client).precheck(any());
        assertThat(service.compare(7L, LegacyOrderRiskDecision.PASS).name()).isEqualTo("ERROR");

        doReturn(null).when(client).precheck(any());
        assertThat(service.compare(7L, LegacyOrderRiskDecision.ACTIVE_ORDER).name()).isEqualTo("ERROR");

        doReturn(ResponseVo.success(
                new OrderLifecycleParticipantResult("UNKNOWN", List.of())))
                .when(client).precheck(any());
        assertThat(service.compare(7L, LegacyOrderRiskDecision.PASS).name()).isEqualTo("ERROR");
    }
}
