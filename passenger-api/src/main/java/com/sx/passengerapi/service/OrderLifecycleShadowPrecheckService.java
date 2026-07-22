package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.PassengerAuthMetrics;
import com.sx.passengerapi.client.OrderLifecycleClient;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.lifecycle.OrderLifecycleParticipantResult;
import com.sx.passengerapi.model.lifecycle.OrderLifecyclePrecheckRequest;
import org.springframework.stereotype.Service;

@Service
public class OrderLifecycleShadowPrecheckService {
    public enum ComparisonResult { MATCH, LEGACY_ONLY, NEW_ONLY, ERROR }

    private final OrderLifecycleClient client;
    private final PassengerAuthMetrics metrics;

    public OrderLifecycleShadowPrecheckService(OrderLifecycleClient client, PassengerAuthMetrics metrics) {
        this.client = client;
        this.metrics = metrics;
    }

    public ComparisonResult compare(long customerId, LegacyOrderRiskDecision legacy) {
        ComparisonResult comparison;
        try {
            ResponseVo<OrderLifecycleParticipantResult> response =
                    client.precheck(new OrderLifecyclePrecheckRequest(customerId));
            if (response == null || response.getCode() == null || response.getCode() != 200
                    || response.getData() == null) {
                comparison = ComparisonResult.ERROR;
            } else {
                String decision = response.getData().decision();
                if (!"PASS".equals(decision) && !"BLOCKED".equals(decision)) {
                    comparison = ComparisonResult.ERROR;
                } else {
                    boolean currentBlocked = "BLOCKED".equals(decision);
                    comparison = legacy.blocked() == currentBlocked
                            ? ComparisonResult.MATCH
                            : legacy.blocked() ? ComparisonResult.LEGACY_ONLY : ComparisonResult.NEW_ONLY;
                }
            }
        } catch (RuntimeException ex) {
            comparison = ComparisonResult.ERROR;
        }
        try {
            metrics.orderShadow(PassengerAuthMetrics.OrderShadowResult.valueOf(comparison.name()));
        } catch (RuntimeException ignored) {
            // 影子指标故障不得改变旧注销裁决。
        }
        return comparison;
    }
}
