package com.sx.order.client;

import com.sx.order.common.vo.ResponseVo;
import com.sx.order.model.dto.PaymentAttemptRequest;
import com.sx.order.model.dto.PaymentAttemptResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WalletPaymentClient {
    private final RestClient restClient;

    public WalletPaymentClient(RestClient.Builder builder,
                               @Value("${services.wallet.base-url:http://127.0.0.1:8095}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public PaymentAttemptResult createPaymentAttempt(PaymentAttemptRequest request) {
        ResponseVo<PaymentAttemptResult> response = restClient.post()
                .uri("/internal/wallet/payment-attempts")
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return unwrap(response);
    }

    public PaymentAttemptResult getPaymentAttempt(String paymentNo) {
        ResponseVo<PaymentAttemptResult> response = restClient.get()
                .uri("/internal/wallet/payment-attempts/{paymentNo}", paymentNo)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return unwrap(response);
    }

    private PaymentAttemptResult unwrap(ResponseVo<PaymentAttemptResult> response) {
        if (response == null) {
            throw new IllegalStateException("钱包服务无响应");
        }
        if (!Integer.valueOf(200).equals(response.getCode())) {
            String message = response.getMsg() == null ? "钱包支付失败" : response.getMsg();
            if (message.contains("默认免密支付")) {
                throw new NoDefaultAgreementException(message);
            }
            throw new IllegalArgumentException(message);
        }
        if (response.getData() == null) {
            throw new IllegalStateException("钱包服务未返回支付尝试");
        }
        return response.getData();
    }

    public static class NoDefaultAgreementException extends IllegalArgumentException {
        public NoDefaultAgreementException(String message) { super(message); }
    }
}
