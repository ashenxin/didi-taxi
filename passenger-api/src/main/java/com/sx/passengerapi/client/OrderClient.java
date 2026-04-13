package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ordercore.AssignOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderResult;
import com.sx.passengerapi.model.ordercore.CancelOrderBody;
import com.sx.passengerapi.model.ordercore.OpenDriverOfferBody;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "order", url = "${services.order.base-url:http://127.0.0.1:8093}")
public interface OrderClient {

    @PostMapping("/api/v1/orders")
    ResponseVo<CreateOrderResult> create(@RequestBody CreateOrderBody body);

    @PostMapping("/api/v1/orders/{orderNo}/assign")
    ResponseVo<Void> assign(@PathVariable("orderNo") String orderNo, @RequestBody AssignOrderBody body);

    @PostMapping("/api/v1/orders/{orderNo}/offer/open")
    ResponseVo<Void> openDriverOffer(@PathVariable("orderNo") String orderNo, @RequestBody OpenDriverOfferBody body);

    @GetMapping("/api/v1/orders/{orderNo}")
    ResponseVo<TripOrderRow> getByOrderNo(@PathVariable("orderNo") String orderNo);

    @PostMapping("/api/v1/orders/{orderNo}/cancel")
    ResponseVo<Void> cancel(@PathVariable("orderNo") String orderNo, @RequestBody CancelOrderBody body);
}

