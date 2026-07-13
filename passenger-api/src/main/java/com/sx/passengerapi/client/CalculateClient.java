package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.calculate.EstimateFareBody;
import com.sx.passengerapi.model.calculate.EstimateFareResult;
import com.sx.passengerapi.model.wallet.CouponLockRequest;
import com.sx.passengerapi.model.wallet.CouponLockResult;
import com.sx.passengerapi.model.wallet.CouponPageVO;
import com.sx.passengerapi.model.wallet.CouponClaimRequest;
import com.sx.passengerapi.model.wallet.CouponClaimResult;
import com.sx.passengerapi.model.wallet.CouponInvalidateRequest;
import com.sx.passengerapi.model.wallet.CouponTemplateVO;
import com.sx.passengerapi.model.wallet.CouponUseRequest;
import com.sx.passengerapi.model.wallet.CouponVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

@FeignClient(name = "calculate", url = "${services.calculate.base-url:http://127.0.0.1:8091}")
public interface CalculateClient {

    @PostMapping("/api/v1/calculate/estimate")
    ResponseVo<EstimateFareResult> estimate(@RequestBody EstimateFareBody body);

    @GetMapping("/api/v1/coupons")
    ResponseVo<CouponPageVO> pageCoupons(@RequestParam("passengerId") Long passengerId,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                         @RequestParam(value = "pageSize", required = false) Integer pageSize);

    @GetMapping("/api/v1/coupons/available")
    ResponseVo<List<CouponVO>> availableCoupons(@RequestParam("passengerId") Long passengerId,
                                                @RequestParam("companyId") Long companyId,
                                                @RequestParam("finalAmount") BigDecimal finalAmount,
                                                @RequestParam("cityCode") String cityCode,
                                                @RequestParam("productCode") String productCode);

    @GetMapping("/api/v1/coupons/claimable")
    ResponseVo<List<CouponTemplateVO>> claimableCoupons(@RequestParam("passengerId") Long passengerId,
                                                        @RequestParam(value = "claimIdentityType", required = false) String claimIdentityType,
                                                        @RequestParam(value = "claimIdentityHash", required = false) String claimIdentityHash);

    @PostMapping("/api/v1/coupons/claim-all")
    ResponseVo<CouponClaimResult> claimAllCoupons(@RequestParam("passengerId") Long passengerId,
                                                  @RequestBody CouponClaimRequest request);

    @PostMapping("/api/v1/coupons/claim")
    ResponseVo<CouponClaimResult> claimCoupons(@RequestParam("passengerId") Long passengerId,
                                               @RequestBody CouponClaimRequest request);

    @PostMapping("/internal/calculate/coupons/lock")
    ResponseVo<CouponLockResult> lockCoupon(@RequestBody CouponLockRequest request);

    @PostMapping("/internal/calculate/coupons/use")
    ResponseVo<Void> useCoupon(@RequestBody CouponUseRequest request);

    @PostMapping("/internal/calculate/coupons/release")
    ResponseVo<Void> releaseCoupon(@RequestBody CouponUseRequest request);

    @GetMapping("/internal/calculate/coupons/locked-exists")
    ResponseVo<Boolean> lockedCouponsExists(@RequestParam("passengerId") Long passengerId);

    @PostMapping("/internal/calculate/coupons/invalidate-by-passenger")
    ResponseVo<Integer> invalidateCouponsByPassenger(@RequestBody CouponInvalidateRequest request);
}
