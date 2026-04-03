package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.capacity.NearestDriverResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "capacity", url = "${services.capacity.base-url:http://127.0.0.1:8090}")
public interface CapacityDispatchClient {

    @GetMapping("/api/v1/dispatch/nearest-driver")
    ResponseVo<NearestDriverResult> nearestDriver(@RequestParam("cityCode") String cityCode,
                                                  @RequestParam(value = "productCode", required = false) String productCode);
}

