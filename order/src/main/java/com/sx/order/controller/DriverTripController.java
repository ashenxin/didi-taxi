package com.sx.order.controller;
import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.model.dto.*;
import com.sx.order.service.DriverTripQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/driver-trips")
public class DriverTripController {
    private final DriverTripQueryService service;
    public DriverTripController(DriverTripQueryService service) { this.service=service; }
    @GetMapping
    public ResponseVo<DriverTripPage> page(@RequestHeader("X-User-Id") long driverId,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue="ACCEPTED") String dateField,
            @RequestParam(defaultValue="1") int pageNo, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) Long snapshotId) {
        return ResultUtil.success(service.page(driverId,status,startDate,endDate,dateField,pageNo,pageSize,snapshotId));
    }
    @GetMapping("/{tripId}")
    public ResponseVo<DriverTripView> detail(@RequestHeader("X-User-Id") long driverId,@PathVariable long tripId) {
        return ResultUtil.success(service.detail(driverId,tripId));
    }
    @GetMapping("/today")
    public ResponseVo<DriverTodaySummary> today(@RequestHeader("X-User-Id") long driverId) {
        return ResultUtil.success(service.today(driverId));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ResponseVo<?>> statusError(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ResultUtil.error(ex.getStatusCode().value(),ex.getReason()));
    }
}
