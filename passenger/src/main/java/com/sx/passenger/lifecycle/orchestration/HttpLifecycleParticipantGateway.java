package com.sx.passenger.lifecycle.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * 使用内部 HTTP 接口调用订单、钱包和权益参与者。
 *
 * <p>所有请求携带内部服务 Token；响应必须满足统一 envelope 和结果契约，
 * 非 2xx、空数据或业务码异常都作为技术失败交给编排重试。
 */
@Component
public class HttpLifecycleParticipantGateway implements LifecycleParticipantGateway {
    private static final ParameterizedTypeReference<ResponseEnvelope<LifecycleParticipantResult>> TYPE =
            new ParameterizedTypeReference<>() {};
    private final LifecycleParticipantRegistry registry;
    private final RestClient client;
    private final String token;

    public HttpLifecycleParticipantGateway(
            LifecycleParticipantRegistry registry,
            RestClient.Builder builder,
            @Value("${passenger.account-lifecycle.participant-token}") String token,
            @Value("${passenger.account-lifecycle.participants.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${passenger.account-lifecycle.participants.read-timeout-ms}") long readTimeoutMs) {
        this.registry = registry;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requests.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.client = builder.requestFactory(requests).build();
        this.token = token;
    }

    @Override
    /** 调用步骤白名单中的同步检查地址。 */
    public LifecycleParticipantResult executeCheck(LifecycleParticipantCommand command) {
        var endpoint = registry.require(command.stepCode(), command.targetDomain());
        ResponseEnvelope<LifecycleParticipantResult> response = client.post()
                .uri(endpoint.executeUrl())
                .header("X-Internal-Token", token)
                .body(command)
                .retrieve()
                .body(TYPE);
        return requireSuccess(response);
    }

    @Override
    /** 查询参与者对指定 Operation/Step 的异步执行结果。 */
    public Optional<LifecycleParticipantResult> queryResult(
            String participantCode, String operationNo, String stepCode) {
        var endpoint = registry.require(stepCode, participantCode);
        try {
            ResponseEnvelope<LifecycleParticipantResult> response = client.get()
                    .uri(endpoint.resultRootUrl() + "/{operationNo}/{stepCode}",
                            operationNo, stepCode)
                    .header(HttpHeaders.AUTHORIZATION, "")
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, result) -> {
                        if (result.getStatusCode().value() != 404) {
                            throw new IllegalStateException(
                                    "生命周期参与方结果查询失败: " + result.getStatusCode());
                        }
                    })
                    .body(TYPE);
            if (response == null || response.data() == null) return Optional.empty();
            return Optional.of(requireSuccess(response));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return Optional.empty();
            throw ex;
        }
    }

    /** 解包统一响应；只有 code=200 且 data 非空才视为成功调用。 */
    private static LifecycleParticipantResult requireSuccess(
            ResponseEnvelope<LifecycleParticipantResult> response) {
        if (response == null || response.code() == null || response.code() != 200
                || response.data() == null) {
            throw new IllegalStateException("生命周期参与方返回了非成功响应");
        }
        return response.data();
    }

    private record ResponseEnvelope<T>(Integer code, String error, String msg, T data) {}
}
