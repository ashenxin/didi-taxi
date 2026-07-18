package com.sx.order.controller;

import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.model.dto.PaymentResultNotification;
import com.sx.order.service.TripOrderSettlementService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钱包支付结果通知入口：接收支付单终态并推进订单结算、优惠券核销等后续流程。
 * 该接口仅供 {@code wallet-service} 内部调用。
 */
@RestController
public class SettlementPaymentResultController {
    private final TripOrderSettlementService settlementService;

    public SettlementPaymentResultController(TripOrderSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 幂等处理钱包支付结果通知，并返回本次处理结论。
     * {@code POST /internal/orders/settlements/payment-results}
     */
    @PostMapping("/internal/orders/settlements/payment-results")
    public ResponseVo<String> paymentResult(@RequestBody PaymentResultNotification notification) {
        return ResultUtil.success(settlementService.handlePaymentResult(notification));
    }
}
