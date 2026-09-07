package com.sx.driverapi.client;
import com.sx.driverapi.model.trip.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="order-service", contextId="driverTripClient")
public interface DriverTripClient {
    @GetMapping("/api/v1/driver-trips")
    CoreResponseVo<DriverTripPage> page(@RequestHeader("X-User-Id") String driverId,
        @RequestParam("status") String status, @RequestParam(value="startDate",required=false) String startDate,
        @RequestParam(value="endDate",required=false) String endDate, @RequestParam("dateField") String dateField,
        @RequestParam("pageNo") int pageNo, @RequestParam("pageSize") int pageSize,
        @RequestParam(value="snapshotId",required=false) Long snapshotId);
    @GetMapping("/api/v1/driver-trips/{tripId}")
    CoreResponseVo<DriverTripView> detail(@RequestHeader("X-User-Id") String driverId,@PathVariable("tripId") long tripId);
    @GetMapping("/api/v1/driver-trips/today")
    CoreResponseVo<DriverTodaySummary> today(@RequestHeader("X-User-Id") String driverId);
}
