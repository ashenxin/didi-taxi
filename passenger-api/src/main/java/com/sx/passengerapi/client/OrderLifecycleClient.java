package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.OrderLifecycleFeignConfiguration;
import com.sx.passengerapi.model.lifecycle.OrderLifecycleParticipantResult;
import com.sx.passengerapi.model.lifecycle.OrderLifecyclePrecheckRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "order-lifecycle", url = "${services.order.base-url:http://127.0.0.1:8093}",
        configuration = OrderLifecycleFeignConfiguration.class)
public interface OrderLifecycleClient {
    @PostMapping("/api/v1/internal/account-lifecycle/order/precheck")
    ResponseVo<OrderLifecycleParticipantResult> precheck(@RequestBody OrderLifecyclePrecheckRequest request);
}
