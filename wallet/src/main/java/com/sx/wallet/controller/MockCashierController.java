package com.sx.wallet.controller;

import com.sx.wallet.common.util.ResultUtil;
import com.sx.wallet.common.vo.ResponseVo;
import com.sx.wallet.config.MockPaymentProperties;
import com.sx.wallet.model.dto.PaymentResult;
import com.sx.wallet.model.dto.ResolveMockPaymentRequest;
import com.sx.wallet.service.PaymentAttemptService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 本地联调用 Mock 收银台与支付结果控制接口。
 * 仅在 {@code wallet.mock-payment.enabled=true} 时允许展示收银台和推进模拟支付状态。
 */
@RestController
public class MockCashierController {
    private final PaymentAttemptService paymentAttemptService;
    private final MockPaymentProperties mockProperties;

    public MockCashierController(PaymentAttemptService paymentAttemptService,
                                 MockPaymentProperties mockProperties) {
        this.paymentAttemptService = paymentAttemptService;
        this.mockProperties = mockProperties;
    }

    /**
     * 展示指定支付尝试单的 Mock 收银台 HTML；访问 token 用于校验支付单链接。
     * {@code GET /mock-cashier/{paymentNo}?token=}
     */
    @GetMapping(value = "/mock-cashier/{paymentNo}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> cashier(@PathVariable String paymentNo, @RequestParam String token) {
        requireMockEnabled();
        PaymentResult payment = paymentAttemptService.getMockCashier(paymentNo, token);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(renderCashier(payment, token));
    }

    /**
     * 内部查询指定支付尝试单的当前状态。
     * {@code GET /internal/wallet/payment-attempts/{paymentNo}}
     */
    @GetMapping("/internal/wallet/payment-attempts/{paymentNo}")
    public ResponseVo<PaymentResult> getPaymentAttempt(@PathVariable String paymentNo) {
        return ResultUtil.success(paymentAttemptService.getPaymentAttempt(paymentNo));
    }

    /**
     * Mock 环境推进支付尝试单状态，并触发与真实渠道回调一致的后续处理。
     * {@code POST /internal/wallet/payment-attempts/{paymentNo}/mock-resolve}
     */
    @PostMapping("/internal/wallet/payment-attempts/{paymentNo}/mock-resolve")
    public ResponseVo<PaymentResult> resolve(@PathVariable String paymentNo,
                                             @Valid @RequestBody ResolveMockPaymentRequest request) {
        requireMockEnabled();
        return ResultUtil.success(paymentAttemptService.resolveMockPayment(
                paymentNo, request.getToken(), request.getStatus()));
    }

    private void requireMockEnabled() {
        if (!mockProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "mock支付未启用");
        }
    }

    private String renderCashier(PaymentResult payment, String token) {
        String orderNo = HtmlUtils.htmlEscape(maskOrderNo(payment.getOrderNo()));
        String channel = HtmlUtils.htmlEscape(payment.getChannel() == null ? "-" : payment.getChannel());
        String amount = payment.getAmount() == null ? "-" : payment.getAmount().toPlainString();
        String paymentNo = HtmlUtils.htmlEscape(payment.getPaymentNo());
        String safeToken = HtmlUtils.htmlEscape(token);
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Mock 收银台</title></head><body>
                <main><h1>Mock 收银台</h1>
                <p>订单号：%s</p><p>支付单号：%s</p><p>渠道：%s</p><p>应付金额：¥%s</p>
                <p>当前状态：<strong>%s</strong></p>
                <div id="actions">
                  <button data-status="SUCCESS">支付成功</button>
                  <button data-status="FAILED">支付失败</button>
                  <button data-status="CANCELLED">取消支付</button>
                  <button data-status="CONFIRMING">支付确认中</button>
                </div><pre id="result"></pre></main>
                <script>
                const paymentNo = document.querySelector('p:nth-of-type(2)').textContent.substring(5);
                const token = '%s';
                document.querySelectorAll('button').forEach(button => button.onclick = async () => {
                  const response = await fetch('/internal/wallet/payment-attempts/' + encodeURIComponent(paymentNo) + '/mock-resolve', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({token, status: button.dataset.status})
                  });
                  document.getElementById('result').textContent = await response.text();
                });
                </script></body></html>
                """.formatted(orderNo, paymentNo, channel, amount,
                HtmlUtils.htmlEscape(payment.getStatus()), safeToken);
    }

    private String maskOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return "-";
        }
        if (orderNo.length() <= 4) {
            return "****";
        }
        return orderNo.substring(0, 2) + "*".repeat(orderNo.length() - 4)
                + orderNo.substring(orderNo.length() - 2);
    }
}
