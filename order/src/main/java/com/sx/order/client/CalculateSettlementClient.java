package com.sx.order.client;

import com.sx.order.common.vo.ResponseVo;
import com.sx.order.model.dto.CouponLockRequest;
import com.sx.order.model.dto.CouponLockResult;
import com.sx.order.model.dto.CouponUseRequest;
import com.sx.order.model.dto.FinalFareRequest;
import com.sx.order.model.dto.FinalFareResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CalculateSettlementClient {

    private static final int SUCCESS = 200;
    private final RestClient restClient;

    public CalculateSettlementClient(
            RestClient.Builder builder,
            @Value("${services.calculate.base-url:http://127.0.0.1:8091}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public FinalFareResult calculateFinalFare(FinalFareRequest request) {
        ResponseVo<FinalFareResult> response = restClient.post()
                .uri("/internal/calculate/final-fare")
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return unwrap(response, "最终计价失败");
    }

    public CouponLockResult lockCoupon(CouponLockRequest request) {
        ResponseVo<CouponLockResult> response = restClient.post()
                .uri("/internal/calculate/coupons/lock")
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return unwrap(response, "优惠券锁定失败");
    }

    public void useCoupon(CouponUseRequest request) {
        invokeCouponCommand("/internal/calculate/coupons/use", request, "优惠券核销失败");
    }

    public void releaseCoupon(CouponUseRequest request) {
        invokeCouponCommand("/internal/calculate/coupons/release", request, "优惠券释放失败");
    }

    private void invokeCouponCommand(String uri, CouponUseRequest request, String fallback) {
        ResponseVo<Void> response = restClient.post()
                .uri(uri)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        unwrap(response, fallback);
    }

    private static <T> T unwrap(ResponseVo<T> response, String fallback) {
        if (response == null) {
            throw new IllegalStateException(fallback + "：下游无响应");
        }
        if (!Integer.valueOf(SUCCESS).equals(response.getCode())) {
            throw new IllegalArgumentException(response.getMsg() == null ? fallback : response.getMsg());
        }
        return response.getData();
    }
}
