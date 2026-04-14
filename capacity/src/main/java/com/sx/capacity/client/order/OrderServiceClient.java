package com.sx.capacity.client.order;

import com.sx.capacity.client.order.dto.AssignOrderFeignBody;
import com.sx.capacity.client.order.dto.OpenDriverOfferFeignBody;
import com.sx.capacity.client.order.dto.PendingDispatchFeignDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "order-service", url = "${services.order.base-url:http://127.0.0.1:8093}")
public interface OrderServiceClient {

    @GetMapping("/api/v1/orders/internal/pending-dispatch")
    OrderServiceResponseVo<List<PendingDispatchFeignDto>> listPendingDispatch(
            @RequestParam("cityCode") String cityCode,
            @RequestParam(value = "limit", required = false) Integer limit);

    @PostMapping("/api/v1/orders/{orderNo}/assign")
    OrderServiceResponseVo<Void> assign(@PathVariable("orderNo") String orderNo,
                                        @RequestBody AssignOrderFeignBody body);

    @PostMapping("/api/v1/orders/{orderNo}/offer/open")
    OrderServiceResponseVo<Void> openDriverOffer(@PathVariable("orderNo") String orderNo,
                                                 @RequestBody OpenDriverOfferFeignBody body);
}
