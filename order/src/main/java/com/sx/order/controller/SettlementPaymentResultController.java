package com.sx.order.controller;

import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.model.dto.PaymentResultNotification;
import com.sx.order.service.TripOrderSettlementService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettlementPaymentResultController {
    private final TripOrderSettlementService settlementService;

    public SettlementPaymentResultController(TripOrderSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/internal/orders/settlements/payment-results")
    public ResponseVo<String> paymentResult(@RequestBody PaymentResultNotification notification) {
        return ResultUtil.success(settlementService.handlePaymentResult(notification));
    }
}
