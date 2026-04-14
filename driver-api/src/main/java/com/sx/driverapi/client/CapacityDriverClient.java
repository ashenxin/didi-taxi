package com.sx.driverapi.client;

import com.sx.driverapi.model.capacity.CapacityDriverSnapshot;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "capacity-driver", url = "${services.capacity.base-url:http://127.0.0.1:8090}")
public interface CapacityDriverClient {

    @PostMapping("/api/v1/drivers/{driverId}/online")
    CoreResponseVo<Void> setOnline(@PathVariable("driverId") Long driverId, @RequestBody DriverOnlineBody body);

    @GetMapping("/api/v1/drivers/{driverId}")
    CoreResponseVo<CapacityDriverSnapshot> getDriver(@PathVariable("driverId") Long driverId);

    @GetMapping("/api/v1/drivers/{driverId}/accept-readiness")
    CoreResponseVo<Void> acceptReadiness(@PathVariable("driverId") Long driverId);
}
