package com.sx.driverapi.client;

import com.sx.driverapi.model.capacity.CapacityDriverDetail;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import com.sx.driverapi.model.teamchange.CapacityCompanyRow;
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
    CoreResponseVo<CapacityDriverDetail> getDriver(@PathVariable("driverId") Long driverId);

    @GetMapping("/api/v1/drivers/{driverId}/accept-readiness")
    CoreResponseVo<Void> acceptReadiness(@PathVariable("driverId") Long driverId);

    @GetMapping("/api/v1/companies/{id}")
    CoreResponseVo<CapacityCompanyRow> getCompany(@PathVariable("id") Long id);
}
