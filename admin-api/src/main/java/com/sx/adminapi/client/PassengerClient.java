package com.sx.adminapi.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "passenger", url = "${services.passenger.base-url:http://127.0.0.1:8092}")
public interface PassengerClient {

    @GetMapping("/api/v1/customers/by-phone")
    Map<String, Object> byPhone(@RequestParam("phone") String phone);

    @GetMapping("/api/v1/customers/{passengerId}")
    Map<String, Object> get(@PathVariable("passengerId") String passengerId);
}

