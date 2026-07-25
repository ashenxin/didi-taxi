package com.sx.passenger.lifecycle.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class LifecycleParticipantRegistry {
    private final Map<String, ParticipantEndpoint> byStep;

    public LifecycleParticipantRegistry(
            @Value("${passenger.account-lifecycle.participants.order-base-url}") String order,
            @Value("${passenger.account-lifecycle.participants.calculate-base-url}") String calculate,
            @Value("${passenger.account-lifecycle.participants.wallet-base-url}") String wallet) {
        this.byStep = Map.of(
                "ORDER_FINAL_CHECK", endpoint("ORDER", order, "/fence"),
                "CALCULATE_FINAL_CHECK", endpoint("CALCULATE", calculate, "/fence"),
                "WALLET_FINAL_CHECK", endpoint("WALLET", wallet, "/fence"),
                "CALCULATE_INVALIDATE_UNUSED_COUPONS", endpoint("CALCULATE", calculate, "/actions"),
                "CALCULATE_CLEAR_POINTS", endpoint("CALCULATE", calculate, "/actions"),
                "WALLET_CLOSE_AUTO_PAY", endpoint("WALLET", wallet, "/actions"));
    }

    public ParticipantEndpoint require(String stepCode, String participantCode) {
        ParticipantEndpoint endpoint = byStep.get(stepCode);
        if (endpoint == null || !endpoint.participantCode().equals(participantCode)) {
            throw new IllegalStateException("生命周期步骤没有唯一参与方适配器: " + stepCode);
        }
        return endpoint;
    }

    public Set<String> remoteStepCodes() {
        return byStep.keySet();
    }

    public boolean supportsCommand(String stepCode, String participantCode) {
        if ("SESSION_CLOSE_WS".equals(stepCode) && "SESSION".equals(participantCode)) return true;
        ParticipantEndpoint endpoint = byStep.get(stepCode);
        return endpoint != null && endpoint.participantCode().equals(participantCode);
    }

    private static ParticipantEndpoint endpoint(
            String participant, String baseUrl, String actionPath) {
        String root = baseUrl.replaceAll("/+$", "")
                + "/api/v1/internal/account-lifecycle/" + participant.toLowerCase();
        return new ParticipantEndpoint(participant, root + actionPath, root + "/results");
    }

    public record ParticipantEndpoint(
            String participantCode, String executeUrl, String resultRootUrl) {}
}
