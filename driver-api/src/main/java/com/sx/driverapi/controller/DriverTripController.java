package com.sx.driverapi.controller;
import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.common.util.ResultUtil;
import com.sx.driverapi.common.vo.ResponseVo;
import com.sx.driverapi.model.trip.*;
import com.sx.driverapi.service.DriverTripBffService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/driver/api/v1")
public class DriverTripController {
    private final DriverTripBffService service;
    public DriverTripController(DriverTripBffService service) { this.service=service; }
    @GetMapping("/profile/orders")
    public ResponseVo<DriverTripPage> page(@RequestHeader(value="X-User-Id",required=false) String userId,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue="ACCEPTED") String dateField,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) Long snapshotId) {
        return ResultUtil.success(service.page(driver(userId),status,startDate,endDate,dateField,pageNo,pageSize,snapshotId));
    }
    @GetMapping("/profile/orders/{tripId}")
    public ResponseVo<DriverTripView> detail(@RequestHeader(value="X-User-Id",required=false) String userId,@PathVariable long tripId) {
        return ResultUtil.success(service.detail(driver(userId),tripId));
    }
    @GetMapping("/dashboard/today")
    public ResponseVo<DriverTodaySummary> today(@RequestHeader(value="X-User-Id",required=false) String userId) {
        return ResultUtil.success(service.today(driver(userId)));
    }
    private long driver(String id) {
        try { long value=Long.parseLong(id==null?"":id.trim()); if(value>0)return value; }
        catch(NumberFormatException ignored) { }
        throw new BizErrorException(401,"未授权，请重新登录");
    }
}
