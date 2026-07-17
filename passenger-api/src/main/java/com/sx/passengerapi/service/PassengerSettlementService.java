package com.sx.passengerapi.service;

import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.model.settlement.ManualPaymentCommand;
import com.sx.passengerapi.model.settlement.PaymentInvokeResult;
import com.sx.passengerapi.model.settlement.SettlementDetailVO;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

@Service
public class PassengerSettlementService {
    private final OrderClient orderClient;
    private final String walletBaseUrl;

    public PassengerSettlementService(OrderClient orderClient,
            @Value("${services.wallet.base-url:http://127.0.0.1:8095}") String walletBaseUrl) {
        this.orderClient = orderClient;
        this.walletBaseUrl = walletBaseUrl == null ? "" : walletBaseUrl.replaceAll("/+$", "");
    }

    public SettlementDetailVO get(String orderNo, Long passengerId) {
        var response = orderClient.passengerSettlement(orderNo, passengerId);
        if (response == null || response.getCode() == null || response.getCode() != 200 || response.getData() == null) {
            throw downstream(response == null ? null : response.getCode(),
                    response == null ? null : response.getMsg(), "查询结算失败");
        }
        var row = response.getData();
        boolean payable = "PAYMENT_REQUIRED".equals(row.getSettlementStatus())
                && !Integer.valueOf(1).equals(row.getManualActionRequired());
        List<String> channels = payable ? List.of("ALIPAY", "WECHAT") : List.of();
        String message = switch (row.getSettlementStatus() == null ? "CALCULATING" : row.getSettlementStatus()) {
            case "PAID" -> "支付成功";
            case "PAYMENT_REQUIRED" -> payable ? "请选择支付方式完成支付" : "结算异常，请联系运营";
            case "PAY_CONFIRMING" -> "支付结果确认中，请稍候";
            default -> "订单正在结算，请稍候";
        };
        String couponName = row.getCouponCompanyNameSnapshot() != null
                ? row.getCouponCompanyNameSnapshot() : row.getCouponType();
        return new SettlementDetailVO(row.getSettlementStatus(), row.getFinalAmount(),
                row.getCouponDiscountAmount(), row.getPayableAmount(), row.getPaidAmount(),
                couponName, channels, message);
    }

    public PaymentInvokeResult pay(String orderNo, Long passengerId, String idempotencyKey,
                                   String channel) {
        var response = orderClient.createManualPayment(orderNo, passengerId, idempotencyKey,
                new ManualPaymentCommand(channel));
        if (response == null || response.getCode() == null || response.getCode() != 200 || response.getData() == null) {
            throw downstream(response == null ? null : response.getCode(),
                    response == null ? null : response.getMsg(), "主动支付发起失败");
        }
        var row = response.getData();
        String checkoutUrl = row.getCheckoutUrl();
        if (checkoutUrl != null && checkoutUrl.startsWith("/")) {
            checkoutUrl = walletBaseUrl + checkoutUrl;
        }
        PaymentInvokeResult.InvokePayload payload = checkoutUrl == null ? null
                : new PaymentInvokeResult.InvokePayload("MOCK_CASHIER", checkoutUrl);
        return new PaymentInvokeResult(row.getPaymentNo(), row.getStatus(), payload);
    }

    private BizErrorException downstream(Integer code, String message, String fallback) {
        return new BizErrorException(code == null ? 502 : code,
                message == null || message.isBlank() ? fallback : message);
    }
}
