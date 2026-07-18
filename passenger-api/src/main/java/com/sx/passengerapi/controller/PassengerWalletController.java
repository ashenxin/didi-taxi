package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.wallet.AutoPayAgreementVO;
import com.sx.passengerapi.model.wallet.AutoPaySignRequest;
import com.sx.passengerapi.model.wallet.AutoPaySignResult;
import com.sx.passengerapi.model.wallet.CouponPageVO;
import com.sx.passengerapi.model.wallet.CouponClaimRequest;
import com.sx.passengerapi.model.wallet.CouponClaimResult;
import com.sx.passengerapi.model.wallet.CouponTemplateVO;
import com.sx.passengerapi.model.wallet.CouponVO;
import com.sx.passengerapi.model.wallet.WalletSummaryVO;
import com.sx.passengerapi.service.PassengerWalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 乘客端钱包 BFF：聚合钱包免密协议与计价服务优惠券能力。
 * 统一前缀：{@code /app/api/v1/wallet}；身份取自网关注入的 {@code X-User-Id} 和 {@code X-User-Phone}。
 */
@RestController
@RequestMapping("/app/api/v1/wallet")
public class PassengerWalletController {
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_PHONE_HEADER = "X-User-Phone";

    private final PassengerWalletService passengerWalletService;

    public PassengerWalletController(PassengerWalletService passengerWalletService) {
        this.passengerWalletService = passengerWalletService;
    }

    /**
     * 查询钱包首页摘要，包括默认免密渠道和优惠券数量。
     * {@code GET /app/api/v1/wallet/summary}
     */
    @GetMapping("/summary")
    public ResponseVo<WalletSummaryVO> summary(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerWalletService.summary(requireCustomerId(customerId)));
    }

    /**
     * 查询当前乘客的全部免密支付协议。
     * {@code GET /app/api/v1/wallet/auto-pay/agreements}
     */
    @GetMapping("/auto-pay/agreements")
    public ResponseVo<List<AutoPayAgreementVO>> agreements(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerWalletService.listAgreements(requireCustomerId(customerId)));
    }

    /**
     * 发起支付宝或微信免密签约。
     * {@code POST /app/api/v1/wallet/auto-pay/agreements/sign}
     */
    @PostMapping("/auto-pay/agreements/sign")
    public ResponseVo<AutoPaySignResult> sign(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                              @Valid @RequestBody AutoPaySignRequest request) {
        return ResultUtil.success(passengerWalletService.sign(requireCustomerId(customerId), request));
    }

    /**
     * 将当前乘客名下指定免密协议设为默认渠道。
     * {@code POST /app/api/v1/wallet/auto-pay/agreements/{agreementId}/default}
     */
    @PostMapping("/auto-pay/agreements/{agreementId}/default")
    public ResponseVo<AutoPayAgreementVO> setDefault(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                     @PathVariable Long agreementId) {
        return ResultUtil.success(passengerWalletService.setDefault(requireCustomerId(customerId), agreementId));
    }

    /**
     * 关闭当前乘客名下指定免密协议。
     * {@code POST /app/api/v1/wallet/auto-pay/agreements/{agreementId}/close}
     */
    @PostMapping("/auto-pay/agreements/{agreementId}/close")
    public ResponseVo<AutoPayAgreementVO> close(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                @PathVariable Long agreementId) {
        return ResultUtil.success(passengerWalletService.close(requireCustomerId(customerId), agreementId));
    }

    /**
     * 分页查询当前乘客的优惠券，可按状态筛选。
     * {@code GET /app/api/v1/wallet/coupons?status=&pageNo=&pageSize=}
     */
    @GetMapping("/coupons")
    public ResponseVo<CouponPageVO> coupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return ResultUtil.success(passengerWalletService.pageCoupons(requireCustomerId(customerId), status, pageNo, pageSize));
    }

    /**
     * 查询指定订单当前可用的优惠券。
     * {@code GET /app/api/v1/wallet/coupons/available?orderNo=}
     */
    @GetMapping("/coupons/available")
    public ResponseVo<List<CouponVO>> availableCoupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                       @RequestParam String orderNo) {
        return ResultUtil.success(passengerWalletService.availableCoupons(requireCustomerId(customerId), orderNo));
    }

    /**
     * 查询当前乘客可领取的优惠券模板；手机号仅由可信网关请求头传入。
     * {@code GET /app/api/v1/wallet/coupons/claimable}
     */
    @GetMapping("/coupons/claimable")
    public ResponseVo<List<CouponTemplateVO>> claimableCoupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                               @RequestHeader(value = USER_PHONE_HEADER, required = false) String phone) {
        return ResultUtil.success(passengerWalletService.claimableCoupons(requireCustomerId(customerId), phone));
    }

    /**
     * 一次领取当前乘客满足条件的全部优惠券。
     * {@code POST /app/api/v1/wallet/coupons/claim-all}
     */
    @PostMapping("/coupons/claim-all")
    public ResponseVo<CouponClaimResult> claimAllCoupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                        @RequestHeader(value = USER_PHONE_HEADER, required = false) String phone) {
        return ResultUtil.success(passengerWalletService.claimAllCoupons(requireCustomerId(customerId), phone));
    }

    /**
     * 按模板 ID 列表领取指定优惠券。
     * {@code POST /app/api/v1/wallet/coupons/claim}
     */
    @PostMapping("/coupons/claim")
    public ResponseVo<CouponClaimResult> claimCoupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                      @RequestHeader(value = USER_PHONE_HEADER, required = false) String phone,
                                                      @RequestBody CouponClaimRequest request) {
        return ResultUtil.success(passengerWalletService.claimCoupons(requireCustomerId(customerId), request, phone));
    }

    private static long requireCustomerId(Long customerId) {
        if (customerId == null || customerId <= 0) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        return customerId;
    }
}
