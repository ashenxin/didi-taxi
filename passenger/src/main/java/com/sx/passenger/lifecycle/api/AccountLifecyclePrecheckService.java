package com.sx.passenger.lifecycle.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 集中调用 ORDER、CALCULATE、WALLET 的只读注销预检，任一未知都失败关闭。 */
@Service
public class AccountLifecyclePrecheckService {
    private static final ParameterizedTypeReference<Envelope<ParticipantDecision>> TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String token;
    private final List<Endpoint> endpoints;

    public AccountLifecyclePrecheckService(
            RestClient.Builder builder,
            @Value("${passenger.account-lifecycle.participant-token}") String token,
            @Value("${passenger.account-lifecycle.participants.order-base-url}") String order,
            @Value("${passenger.account-lifecycle.participants.calculate-base-url}") String calculate,
            @Value("${passenger.account-lifecycle.participants.wallet-base-url}") String wallet,
            @Value("${passenger.account-lifecycle.participants.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${passenger.account-lifecycle.participants.read-timeout-ms}") long readTimeoutMs) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requests.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.client = builder.requestFactory(requests).build();
        this.token = token;
        this.endpoints = List.of(
                endpoint("ORDER", order),
                endpoint("CALCULATE", calculate),
                endpoint("WALLET", wallet));
    }

    public AccountLifecyclePrecheckView precheck(long customerId) {
        if (customerId <= 0) throw new IllegalArgumentException("乘客ID非法");
        List<AccountLifecyclePrecheckView.BlockerView> blockers = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            ParticipantDecision decision = invoke(endpoint, customerId);
            if ("UNKNOWN".equalsIgnoreCase(decision.decision())) {
                throw new LifecyclePrecheckUnavailableException(
                        endpoint.domain() + "生命周期预检结果未知");
            }
            if (!"PASS".equalsIgnoreCase(decision.decision())
                    && !"BLOCKED".equalsIgnoreCase(decision.decision())) {
                throw new LifecyclePrecheckUnavailableException(
                        endpoint.domain() + "生命周期预检返回非法裁决");
            }
            for (ParticipantBlocker blocker :
                    decision.blockers() == null ? List.<ParticipantBlocker>of() : decision.blockers()) {
                blockers.add(new AccountLifecyclePrecheckView.BlockerView(
                        endpoint.domain(), blocker.code(), blocker.resourceType(),
                        blocker.resourceNo(), blocker.action()));
            }
        }
        return new AccountLifecyclePrecheckView(
                blockers.isEmpty() ? "PASS" : "BLOCKED", List.copyOf(blockers));
    }

    private ParticipantDecision invoke(Endpoint endpoint, long customerId) {
        try {
            Envelope<ParticipantDecision> response = client.post()
                    .uri(endpoint.url())
                    .header("X-Internal-Token", token)
                    .body(Map.of("customerId", customerId))
                    .retrieve()
                    .body(TYPE);
            if (response == null || response.code() == null || response.code() != 200
                    || response.data() == null || response.data().decision() == null) {
                throw new LifecyclePrecheckUnavailableException(
                        endpoint.domain() + "生命周期预检响应不完整");
            }
            return response.data();
        } catch (LifecyclePrecheckUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LifecyclePrecheckUnavailableException(
                    endpoint.domain() + "生命周期预检不可用", failure);
        }
    }

    private static Endpoint endpoint(String domain, String baseUrl) {
        String root = baseUrl.replaceAll("/+$", "");
        return new Endpoint(domain,
                root + "/api/v1/internal/account-lifecycle/" + domain.toLowerCase() + "/precheck");
    }

    private record Endpoint(String domain, String url) {}
    private record Envelope<T>(Integer code, String msg, T data) {}
    private record ParticipantDecision(String decision, List<ParticipantBlocker> blockers) {}
    private record ParticipantBlocker(
            String code, String resourceType, String resourceNo, String action) {}
}
