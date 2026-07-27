package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.capacity.NearestDriverResult;
import com.sx.passengerapi.model.capacity.PendingOrderIndexBody;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "capacity-service")
public interface CapacityDispatchClient {

    @GetMapping("/api/v1/dispatch/nearest-driver")
    ResponseVo<NearestDriverResult> nearestDriver(@RequestParam("cityCode") String cityCode,
                                                  @RequestParam(value = "productCode", required = false) String productCode,
                                                  @RequestParam(value = "originLat", required = false) Double originLat,
                                                  @RequestParam(value = "originLng", required = false) Double originLng,
                                                  @RequestParam(value = "passengerId", required = false) Long passengerId);

    @PostMapping("/api/v1/dispatch/pending-order-index")
    ResponseVo<Void> addPendingOrderIndex(@RequestBody PendingOrderIndexBody body);
}
