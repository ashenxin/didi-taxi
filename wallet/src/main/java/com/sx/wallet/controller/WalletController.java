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

/**
 * 钱包核心接口：免密支付协议管理、自动扣款和主动支付尝试单创建。
 * 乘客资产接口前缀为 {@code /api/v1/wallet}，支付编排接口前缀为 {@code /internal/wallet}。
 */
@RestController
public class WalletController {
    private final WalletService walletService;
    private final PaymentAttemptService paymentAttemptService;

    public WalletController(WalletService walletService, PaymentAttemptService paymentAttemptService) {
        this.walletService = walletService;
        this.paymentAttemptService = paymentAttemptService;
    }

    /**
     * 查询乘客全部免密支付协议。
     * {@code GET /api/v1/wallet/auto-pay/agreements?passengerId=}
     */
    @GetMapping("/api/v1/wallet/auto-pay/agreements")
    public ResponseVo<List<AutoPayAgreementVO>> listAgreements(@RequestParam Long passengerId) {
        return ResultUtil.success(walletService.listAgreements(passengerId));
    }

    /**
     * 查询乘客当前默认的有效免密支付协议。
     * {@code GET /api/v1/wallet/auto-pay/default?passengerId=}
     */
    @GetMapping("/api/v1/wallet/auto-pay/default")
    public ResponseVo<AutoPayAgreementVO> defaultAgreement(@RequestParam Long passengerId) {
        return ResultUtil.success(walletService.getDefaultAgreement(passengerId));
    }

    /**
     * 发起支付宝或微信免密签约，并返回签约结果。
     * {@code POST /api/v1/wallet/auto-pay/agreements/sign?passengerId=}
     */
    @PostMapping("/api/v1/wallet/auto-pay/agreements/sign")
    public ResponseVo<AutoPaySignResult> sign(@RequestParam Long passengerId,
                                              @Valid @RequestBody AutoPaySignRequest request) {
        try {
            return ResultUtil.success(walletService.sign(passengerId, request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 查询乘客名下指定免密协议；协议不属于该乘客时按不存在处理。
     * {@code GET /api/v1/wallet/auto-pay/agreements/{agreementId}?passengerId=}
     */
    @GetMapping("/api/v1/wallet/auto-pay/agreements/{agreementId}")
    public ResponseVo<AutoPayAgreementVO> getAgreement(@RequestParam Long passengerId,
                                                       @PathVariable Long agreementId) {
        AutoPayAgreementVO agreement = walletService.getAgreement(passengerId, agreementId);
        if (agreement == null) {
            return ResultUtil.error(404, "免密协议不存在");
        }
        return ResultUtil.success(agreement);
    }

    /**
     * 将指定有效免密协议设为乘客默认支付渠道。
     * {@code POST /api/v1/wallet/auto-pay/agreements/{agreementId}/default?passengerId=}
     */
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

    /**
     * 关闭指定免密协议；关闭默认协议后由服务重新选择可用默认渠道。
     * {@code POST /api/v1/wallet/auto-pay/agreements/{agreementId}/close?passengerId=}
     */
    @PostMapping("/api/v1/wallet/auto-pay/agreements/{agreementId}/close")
    public ResponseVo<AutoPayAgreementVO> close(@RequestParam Long passengerId,
                                                @PathVariable Long agreementId) {
        AutoPayAgreementVO agreement = walletService.close(passengerId, agreementId);
        if (agreement == null) {
            return ResultUtil.error(404, "免密协议不存在");
        }
        return ResultUtil.success(agreement);
    }

    /**
     * 结算内部接口：使用乘客默认免密协议创建并执行自动扣款。
     * {@code POST /internal/wallet/payments/auto-pay}
     */
    @PostMapping("/internal/wallet/payments/auto-pay")
    public ResponseVo<PaymentResult> autoPay(@Valid @RequestBody AutoPayRequest request) {
        try {
            return ResultUtil.success(walletService.autoPay(request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 结算内部接口：创建主动支付尝试单并返回支付拉起信息。
     * {@code POST /internal/wallet/payment-attempts}
     */
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
