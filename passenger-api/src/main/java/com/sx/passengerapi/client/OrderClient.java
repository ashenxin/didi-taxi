package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ordercore.AssignOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderResult;
import com.sx.passengerapi.model.ordercore.CancelOrderBody;
import com.sx.passengerapi.model.ordercore.OpenDriverOfferBody;
import com.sx.passengerapi.model.ordercore.OrderEventRow;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.model.ordercore.OrderPageData;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

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

    @GetMapping("/api/v1/orders/{orderNo}/events")
    ResponseVo<List<OrderEventRow>> listEvents(@PathVariable("orderNo") String orderNo);

    /**
     * 分页查询（MVP 内存分页）；用于登出时查找乘客在途单。
     */
    @GetMapping("/api/v1/orders")
    ResponseVo<OrderPageData> pageOrders(
            @RequestParam("passengerId") Long passengerId,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize);

    @PostMapping("/api/v1/orders/{orderNo}/cancel")
    ResponseVo<Void> cancel(@PathVariable("orderNo") String orderNo, @RequestBody CancelOrderBody body);
}

