package com.sx.passengerapi.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Objects;

@Component
public class PassengerAuthMetrics {

    public enum AuthStateResult {
        SUCCESS("success"), INVALID_RESPONSE("invalid_response"), UNAVAILABLE("unavailable");
        private final String tag;
        AuthStateResult(String tag) { this.tag = tag; }
    }

    public enum JwtRejectReason {
        MISSING("missing"), MALFORMED("malformed"), EPOCH_MISMATCH("epoch_mismatch"),
        STATE_MISMATCH("state_mismatch"), RESTRICTED("restricted"), AUTH_STATE_UNAVAILABLE("auth_state_unavailable");
        private final String tag;
        JwtRejectReason(String tag) { this.tag = tag; }
    }

    public enum WsCloseReason {
        LOGOUT("logout"), PHONE_CHANGED("phone_changed"), ACCOUNT_CANCELLING("account_cancelling"),
        ACCOUNT_CANCELLED("account_cancelled"), AUTH_EPOCH_CHANGED("auth_epoch_changed");
        private final String tag;
        WsCloseReason(String tag) { this.tag = tag; }

        public static WsCloseReason from(String reason) {
            return switch (reason) {
                case "logout" -> LOGOUT;
                case "phone_changed" -> PHONE_CHANGED;
                case "account_cancelling" -> ACCOUNT_CANCELLING;
                case "account_cancelled" -> ACCOUNT_CANCELLED;
                default -> AUTH_EPOCH_CHANGED;
            };
        }
    }

    private final MeterRegistry registry;

    @Autowired
    public PassengerAuthMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public PassengerAuthMetrics() {
        this(Metrics.globalRegistry);
    }

    public void authStateQuery(Duration duration, AuthStateResult result) {
        Timer.builder("passenger.auth.state.query").tag("result", result.tag)
                .register(registry).record(duration == null ? Duration.ZERO : duration);
    }

    public void jwtRejected(JwtRejectReason reason) {
        Counter.builder("passenger.auth.jwt.rejected").tag("reason", reason.tag).register(registry).increment();
    }

    public void restrictedIssued() {
        registry.counter("passenger.auth.restricted.issued").increment();
    }

    public void wsClosed(WsCloseReason reason) {
        Counter.builder("passenger.auth.ws.closed").tag("reason", reason.tag).register(registry).increment();
    }
}
