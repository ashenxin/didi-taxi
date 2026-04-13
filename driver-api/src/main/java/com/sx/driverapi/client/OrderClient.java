package com.sx.driverapi.client;

import com.sx.driverapi.model.order.DriverIdBody;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "order", url = "${services.order.base-url:http://127.0.0.1:8093}")
public interface OrderClient {

    @GetMapping("/api/v1/orders/assigned")
    CoreResponseVo<List<TripOrderRow>> listAssigned(@RequestParam("driverId") Long driverId);

    @GetMapping("/api/v1/orders/{orderNo}")
    CoreResponseVo<TripOrderRow> getByOrderNo(@PathVariable("orderNo") String orderNo);

    @PostMapping("/api/v1/orders/{orderNo}/accept")
    CoreResponseVo<Void> accept(@PathVariable("orderNo") String orderNo, @RequestBody DriverIdBody body);

    @PostMapping("/api/v1/orders/{orderNo}/arrive")
    CoreResponseVo<Void> arrive(@PathVariable("orderNo") String orderNo, @RequestBody DriverIdBody body);

    @PostMapping("/api/v1/orders/{orderNo}/start")
    CoreResponseVo<Void> start(@PathVariable("orderNo") String orderNo, @RequestBody DriverIdBody body);

    @PostMapping("/api/v1/orders/{orderNo}/finish")
    CoreResponseVo<Void> finish(@PathVariable("orderNo") String orderNo, @RequestBody FinishOrderBody body);
}
