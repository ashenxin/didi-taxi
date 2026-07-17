package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.settlement.ManualPaymentRequest;
import com.sx.passengerapi.model.settlement.PaymentInvokeResult;
import com.sx.passengerapi.model.settlement.SettlementDetailVO;
import com.sx.passengerapi.service.PassengerSettlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/app/api/v1/orders")
public class PassengerSettlementController {
    private final PassengerSettlementService service;

    public PassengerSettlementController(PassengerSettlementService service) {
        this.service = service;
    }

    @GetMapping("/{orderNo}/settlement")
    public ResponseVo<SettlementDetailVO> get(@PathVariable String orderNo,
            @RequestHeader(value = "X-User-Id", required = false) Long passengerId) {
        requireLogin(passengerId);
        return ResultUtil.success(service.get(orderNo, passengerId));
    }

    @PostMapping("/{orderNo}/payments")
    public ResponseVo<PaymentInvokeResult> pay(@PathVariable String orderNo,
            @RequestHeader(value = "X-User-Id", required = false) Long passengerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ManualPaymentRequest request) {
        requireLogin(passengerId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizErrorException(400, "Idempotency-Key不能为空");
        }
        if (idempotencyKey.trim().length() > 128) {
            throw new BizErrorException(400, "Idempotency-Key长度不能超过128");
        }
        return ResultUtil.success(service.pay(orderNo, passengerId, idempotencyKey, request.channel()));
    }

    private void requireLogin(Long passengerId) {
        if (passengerId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
    }
}
