package com.sx.passengerapi.client;

import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import com.sx.passengerapi.client.dto.InternalLogoutRequest;
import com.sx.passengerapi.client.dto.InternalLogoutResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.PassengerCoreFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "passenger-service", contextId = "passengerCoreAuthState",
        configuration = PassengerCoreFeignConfiguration.class)
public interface PassengerCoreAuthStateClient {

    @GetMapping("/api/v1/internal/auth-state/{customerId}")
    ResponseVo<InternalAuthStateResponse> get(@PathVariable("customerId") long customerId);

    @PostMapping("/api/v1/internal/auth-state/logout")
    ResponseVo<InternalLogoutResponse> logout(@RequestBody InternalLogoutRequest request);
}
