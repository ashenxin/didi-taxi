package com.sx.wallet.controller;

import com.sx.wallet.common.util.ResultUtil;
import com.sx.wallet.common.vo.ResponseVo;
import com.sx.wallet.model.dto.AutoPayAgreementVO;
import com.sx.wallet.model.dto.AutoPayRequest;
import com.sx.wallet.model.dto.AutoPaySignRequest;
import com.sx.wallet.model.dto.AutoPaySignResult;
import com.sx.wallet.model.dto.CreatePaymentAttemptRequest;
import com.sx.wallet.model.dto.PaymentResult;
import com.sx.wallet.service.PaymentAttemptService;
import com.sx.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WalletController {
    private final WalletService walletService;
    private final PaymentAttemptService paymentAttemptService;

    public WalletController(WalletService walletService, PaymentAttemptService paymentAttemptService) {
        this.walletService = walletService;
        this.paymentAttemptService = paymentAttemptService;
    }

    @GetMapping("/api/v1/wallet/auto-pay/agreements")
    public ResponseVo<List<AutoPayAgreementVO>> listAgreements(@RequestParam Long passengerId) {
        return ResultUtil.success(walletService.listAgreements(passengerId));
    }

    @GetMapping("/api/v1/wallet/auto-pay/default")
    public ResponseVo<AutoPayAgreementVO> defaultAgreement(@RequestParam Long passengerId) {
        return ResultUtil.success(walletService.getDefaultAgreement(passengerId));
    }

    @PostMapping("/api/v1/wallet/auto-pay/agreements/sign")
    public ResponseVo<AutoPaySignResult> sign(@RequestParam Long passengerId,
                                              @Valid @RequestBody AutoPaySignRequest request) {
        try {
            return ResultUtil.success(walletService.sign(passengerId, request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @GetMapping("/api/v1/wallet/auto-pay/agreements/{agreementId}")
    public ResponseVo<AutoPayAgreementVO> getAgreement(@RequestParam Long passengerId,
                                                       @PathVariable Long agreementId) {
        AutoPayAgreementVO agreement = walletService.getAgreement(passengerId, agreementId);
        if (agreement == null) {
            return ResultUtil.error(404, "免密协议不存在");
        }
        return ResultUtil.success(agreement);
    }

    @PostMapping("/api/v1/wallet/auto-pay/agreements/{agreementId}/default")
    public ResponseVo<AutoPayAgreementVO> setDefault(@RequestParam Long passengerId,
                                                     @PathVariable Long agreementId) {
        try {
            AutoPayAgreementVO agreement = walletService.setDefault(passengerId, agreementId);
            if (agreement == null) {
                return ResultUtil.error(404, "免密协议不存在");
            }
            return ResultUtil.success(agreement);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/api/v1/wallet/auto-pay/agreements/{agreementId}/close")
    public ResponseVo<AutoPayAgreementVO> close(@RequestParam Long passengerId,
                                                @PathVariable Long agreementId) {
        AutoPayAgreementVO agreement = walletService.close(passengerId, agreementId);
        if (agreement == null) {
            return ResultUtil.error(404, "免密协议不存在");
        }
        return ResultUtil.success(agreement);
    }

    @PostMapping("/internal/wallet/payments/auto-pay")
    public ResponseVo<PaymentResult> autoPay(@Valid @RequestBody AutoPayRequest request) {
        try {
            return ResultUtil.success(walletService.autoPay(request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/internal/wallet/payment-attempts")
    public ResponseVo<PaymentResult> createPaymentAttempt(
            @Valid @RequestBody CreatePaymentAttemptRequest request) {
        try {
            return ResultUtil.success(paymentAttemptService.create(request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }
}
