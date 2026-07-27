package com.sx.driverapi.client;

import com.sx.driverapi.model.order.DriverIdBody;
import com.sx.driverapi.model.order.DriverOrderReasonBody;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import com.sx.driverapi.model.ordercore.DriverActionResult;
import com.sx.driverapi.model.ordercore.AcceptOrderPreflightResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/api/v1/orders/assigned")
    CoreResponseVo<List<TripOrderRow>> listAssigned(@RequestParam("driverId") Long driverId,
                                                    @RequestHeader("X-User-Id") String userId);

    @GetMapping("/api/v1/orders/accepted")
    CoreResponseVo<List<TripOrderRow>> listAcceptedBeforeArrive(@RequestParam("driverId") Long driverId,
                                                                @RequestHeader("X-User-Id") String userId);

    @GetMapping("/api/v1/orders/{orderNo}")
    CoreResponseVo<TripOrderRow> getByOrderNo(@PathVariable("orderNo") String orderNo);

    @PostMapping("/api/v1/orders/{orderNo}/accept-preflight")
    CoreResponseVo<AcceptOrderPreflightResult> acceptPreflight(
            @PathVariable("orderNo") String orderNo,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DriverIdBody body);

    @PostMapping("/api/v1/orders/{orderNo}/accept")
    CoreResponseVo<DriverActionResult> accept(@PathVariable("orderNo") String orderNo,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @RequestBody DriverIdBody body);

    @PostMapping("/api/v1/orders/{orderNo}/reject")
    CoreResponseVo<DriverActionResult> reject(@PathVariable("orderNo") String orderNo,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @RequestBody DriverOrderReasonBody body);

    @PostMapping("/api/v1/orders/{orderNo}/driver/cancel")
    CoreResponseVo<DriverActionResult> driverCancelBeforeArrive(@PathVariable("orderNo") String orderNo,
                                                  @RequestHeader("X-User-Id") String userId,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @RequestBody DriverOrderReasonBody body);

    @PostMapping("/api/v1/orders/{orderNo}/arrive")
    CoreResponseVo<DriverActionResult> arrive(@PathVariable("orderNo") String orderNo,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @RequestBody DriverIdBody body);

    @PostMapping("/api/v1/orders/{orderNo}/start")
    CoreResponseVo<DriverActionResult> start(@PathVariable("orderNo") String orderNo,
                               @RequestHeader("X-User-Id") String userId,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @RequestBody DriverIdBody body);

    @PostMapping("/api/v1/orders/{orderNo}/finish")
    CoreResponseVo<DriverActionResult> finish(@PathVariable("orderNo") String orderNo,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @RequestBody FinishOrderBody body);
}
