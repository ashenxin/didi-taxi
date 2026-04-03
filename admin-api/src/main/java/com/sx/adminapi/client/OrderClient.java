package com.sx.adminapi.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "order", url = "${services.order.base-url:http://127.0.0.1:8093}")
public interface OrderClient {

    @GetMapping("/api/v1/orders")
    Map<String, Object> page(@RequestParam Map<String, Object> params);

    @GetMapping("/api/v1/orders/{orderNo}")
    Map<String, Object> detail(@PathVariable("orderNo") String orderNo);

    @GetMapping("/api/v1/orders/{orderNo}/events")
    Map<String, Object> events(@PathVariable("orderNo") String orderNo);
}

