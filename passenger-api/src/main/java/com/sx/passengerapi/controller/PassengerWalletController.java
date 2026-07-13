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

@RestController
@RequestMapping("/app/api/v1/wallet")
public class PassengerWalletController {
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_PHONE_HEADER = "X-User-Phone";

    private final PassengerWalletService passengerWalletService;

    public PassengerWalletController(PassengerWalletService passengerWalletService) {
        this.passengerWalletService = passengerWalletService;
    }

    @GetMapping("/summary")
    public ResponseVo<WalletSummaryVO> summary(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerWalletService.summary(requireCustomerId(customerId)));
    }

    @GetMapping("/auto-pay/agreements")
    public ResponseVo<List<AutoPayAgreementVO>> agreements(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerWalletService.listAgreements(requireCustomerId(customerId)));
    }

    @PostMapping("/auto-pay/agreements/sign")
    public ResponseVo<AutoPaySignResult> sign(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                              @Valid @RequestBody AutoPaySignRequest request) {
        return ResultUtil.success(passengerWalletService.sign(requireCustomerId(customerId), request));
    }

    @PostMapping("/auto-pay/agreements/{agreementId}/default")
    public ResponseVo<AutoPayAgreementVO> setDefault(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                     @PathVariable Long agreementId) {
        return ResultUtil.success(passengerWalletService.setDefault(requireCustomerId(customerId), agreementId));
    }

    @PostMapping("/auto-pay/agreements/{agreementId}/close")
    public ResponseVo<AutoPayAgreementVO> close(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                @PathVariable Long agreementId) {
        return ResultUtil.success(passengerWalletService.close(requireCustomerId(customerId), agreementId));
    }

    @GetMapping("/coupons")
    public ResponseVo<CouponPageVO> coupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return ResultUtil.success(passengerWalletService.pageCoupons(requireCustomerId(customerId), status, pageNo, pageSize));
    }

    @GetMapping("/coupons/available")
    public ResponseVo<List<CouponVO>> availableCoupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                       @RequestParam String orderNo) {
        return ResultUtil.success(passengerWalletService.availableCoupons(requireCustomerId(customerId), orderNo));
    }

    @GetMapping("/coupons/claimable")
    public ResponseVo<List<CouponTemplateVO>> claimableCoupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                               @RequestHeader(value = USER_PHONE_HEADER, required = false) String phone) {
        return ResultUtil.success(passengerWalletService.claimableCoupons(requireCustomerId(customerId), phone));
    }

    @PostMapping("/coupons/claim-all")
    public ResponseVo<CouponClaimResult> claimAllCoupons(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                        @RequestHeader(value = USER_PHONE_HEADER, required = false) String phone) {
        return ResultUtil.success(passengerWalletService.claimAllCoupons(requireCustomerId(customerId), phone));
    }

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
