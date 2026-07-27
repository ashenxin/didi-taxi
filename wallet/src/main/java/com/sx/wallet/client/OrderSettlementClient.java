package com.sx.wallet.client;

import com.sx.wallet.common.vo.ResponseVo;
import com.sx.wallet.model.dto.PaymentResultNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderSettlementClient {
    private final RestClient restClient;

    public OrderSettlementClient(RestClient.Builder builder,
                                 @Value("${services.order.base-url:http://order-service}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public String notifyPaymentResult(PaymentResultNotification notification) {
        ResponseVo<String> response = restClient.post()
                .uri("/internal/orders/settlements/payment-results")
                .body(notification)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        if (response == null || response.getCode() != 200) {
            throw new IllegalStateException(response == null ? "订单服务无响应" : response.getMsg());
        }
        return response.getData();
    }
}
