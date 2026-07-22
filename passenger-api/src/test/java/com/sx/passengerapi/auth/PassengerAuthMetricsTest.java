package com.sx.passengerapi.auth;

import com.sx.passengerapi.auth.action.PassengerActionCode;
import com.sx.passengerapi.auth.action.PassengerActionDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class PassengerAuthMetricsTest {

    @Test
    void metricRegistryFailuresNeverEscapePublicRecordMethods() {
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(mock(MeterRegistry.class));

        assertThatCode(() -> metrics.authStateQuery(Duration.ofMillis(1),
                PassengerAuthMetrics.AuthStateResult.UNAVAILABLE)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.MALFORMED))
                .doesNotThrowAnyException();
        assertThatCode(metrics::restrictedIssued).doesNotThrowAnyException();
        assertThatCode(() -> metrics.wsClosed(PassengerAuthMetrics.WsCloseReason.LOGOUT))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics.actionDecision(PassengerActionCode.RIDE_CREATE,
                PassengerActionDecision.DENY)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.orderShadow(PassengerAuthMetrics.OrderShadowResult.ERROR))
                .doesNotThrowAnyException();
    }

    @Test
    void recordsDatabaseDecisionAndRejectReasonWithFixedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PassengerAuthMetrics metrics = new PassengerAuthMetrics(registry);

        metrics.authStateQuery(Duration.ofMillis(12), PassengerAuthMetrics.AuthStateResult.SUCCESS);
        metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.EPOCH_MISMATCH);
        metrics.restrictedIssued();
        metrics.wsClosed(PassengerAuthMetrics.WsCloseReason.LOGOUT);
        metrics.actionDecision(PassengerActionCode.ORDER_CANCEL, PassengerActionDecision.ALLOW);
        metrics.orderShadow(PassengerAuthMetrics.OrderShadowResult.NEW_ONLY);

        assertThat(registry.get("passenger.auth.state.query").tag("result", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("passenger.auth.jwt.rejected").tag("reason", "epoch_mismatch").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("passenger.auth.restricted.issued").counter().count()).isEqualTo(1);
        assertThat(registry.get("passenger.auth.ws.closed").tag("reason", "logout").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("passenger.lifecycle.action.decision")
                .tag("actionCode", "order_cancel").tag("decision", "allow").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("passenger.lifecycle.order_shadow")
                .tag("result", "new_only").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(tag.getKey()).isIn("result", "reason", "actionCode", "decision");
            assertThat(tag.getValue()).doesNotContain("7", "13800138000", "op-", "token", "exception");
        }));
    }
}
