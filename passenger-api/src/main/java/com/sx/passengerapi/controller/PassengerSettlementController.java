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

/**
 * 乘客端订单结算 BFF：查询权威账单并发起未结清订单的主动支付。
 * 统一前缀：{@code /app/api/v1/orders}；乘客身份取自网关注入的 {@code X-User-Id}。
 */
@RestController
@RequestMapping("/app/api/v1/orders")
public class PassengerSettlementController {
    private final PassengerSettlementService service;

    public PassengerSettlementController(PassengerSettlementService service) {
        this.service = service;
    }

    /**
     * 查询当前乘客名下订单的结算详情与支付状态。
     * {@code GET /app/api/v1/orders/{orderNo}/settlement}
     */
    @GetMapping("/{orderNo}/settlement")
    public ResponseVo<SettlementDetailVO> get(@PathVariable String orderNo,
            @RequestHeader(value = "X-User-Id", required = false) Long passengerId) {
        requireLogin(passengerId);
        return ResultUtil.success(service.get(orderNo, passengerId));
    }

    /**
     * 为当前乘客的未结清订单发起主动支付；每次支付尝试须携带新的 {@code Idempotency-Key}。
     * {@code POST /app/api/v1/orders/{orderNo}/payments}
     */
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
