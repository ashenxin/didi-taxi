package com.sx.driverapi.client;

import com.sx.driverapi.model.passenger.OrderChangedNotifyBody;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "passenger-api", url = "${services.passenger-api.base-url:http://127.0.0.1:18080}")
public interface PassengerNotifyClient {

    @PostMapping("/app/internal/v1/orders/changed")
    CoreResponseVo<Void> orderChanged(@RequestBody OrderChangedNotifyBody body);
}
