package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.calculate.EstimateFareBody;
import com.sx.passengerapi.model.calculate.EstimateFareResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "calculate", url = "${services.calculate.base-url:http://127.0.0.1:8091}")
public interface CalculateClient {

    @PostMapping("/api/v1/calculate/estimate")
    ResponseVo<EstimateFareResult> estimate(@RequestBody EstimateFareBody body);
}

