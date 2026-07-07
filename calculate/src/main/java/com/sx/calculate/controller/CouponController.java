package com.sx.calculate.controller;

import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.model.dto.CouponLockRequest;
import com.sx.calculate.model.dto.CouponLockResult;
import com.sx.calculate.model.dto.CouponPageVO;
import com.sx.calculate.model.dto.CouponClaimResult;
import com.sx.calculate.model.dto.CouponTemplatePageVO;
import com.sx.calculate.model.dto.CouponTemplateUpsertRequest;
import com.sx.calculate.model.dto.CouponTemplateVO;
import com.sx.calculate.model.dto.CouponUseRequest;
import com.sx.calculate.model.dto.CouponVO;
import com.sx.calculate.service.CouponService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
public class CouponController {
    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/api/v1/coupons")
    public ResponseVo<CouponPageVO> page(@RequestParam Long passengerId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                         @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return ResultUtil.success(couponService.page(passengerId, status, pageNo, pageSize));
    }

    @GetMapping("/api/v1/coupons/available")
    public ResponseVo<List<CouponVO>> available(@RequestParam Long passengerId,
                                                @RequestParam Long companyId,
                                                @RequestParam BigDecimal finalAmount,
                                                @RequestParam String cityCode,
                                                @RequestParam String productCode) {
        return ResultUtil.success(couponService.listAvailable(passengerId, companyId, finalAmount, cityCode, productCode));
    }

    @GetMapping("/api/v1/coupons/claimable")
    public ResponseVo<List<CouponTemplateVO>> claimable(@RequestParam Long passengerId) {
        return ResultUtil.success(couponService.claimable(passengerId));
    }

    @PostMapping("/api/v1/coupons/claim-all")
    public ResponseVo<CouponClaimResult> claimAll(@RequestParam Long passengerId) {
        return ResultUtil.success(couponService.claimAll(passengerId));
    }

    @GetMapping("/api/v1/coupons/templates")
    public ResponseVo<CouponTemplatePageVO> templates(@RequestParam(required = false) Long companyId,
                                                      @RequestParam(required = false) String cityCode,
                                                      @RequestParam(required = false) String productCode,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                                      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return ResultUtil.success(couponService.pageTemplates(companyId, cityCode, productCode, status, pageNo, pageSize));
    }

    @PostMapping("/api/v1/coupons/templates")
    public ResponseVo<CouponTemplateVO> createTemplate(@Valid @RequestBody CouponTemplateUpsertRequest request) {
        try {
            return ResultUtil.success(couponService.createTemplate(request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PutMapping("/api/v1/coupons/templates/{templateId}")
    public ResponseVo<CouponTemplateVO> updateTemplate(@PathVariable Long templateId,
                                                       @Valid @RequestBody CouponTemplateUpsertRequest request) {
        try {
            return ResultUtil.success(couponService.updateTemplate(templateId, request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/api/v1/coupons/templates/{templateId}/publish")
    public ResponseVo<CouponTemplateVO> publishTemplate(@PathVariable Long templateId,
                                                        @RequestParam(required = false) Long operatorId) {
        try {
            return ResultUtil.success(couponService.publishTemplate(templateId, operatorId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/api/v1/coupons/templates/{templateId}/offline")
    public ResponseVo<CouponTemplateVO> offlineTemplate(@PathVariable Long templateId,
                                                        @RequestParam(required = false) Long operatorId) {
        try {
            return ResultUtil.success(couponService.offlineTemplate(templateId, operatorId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/internal/calculate/coupons/lock")
    public ResponseVo<CouponLockResult> lock(@Valid @RequestBody CouponLockRequest request) {
        try {
            return ResultUtil.success(couponService.lock(request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/internal/calculate/coupons/use")
    public ResponseVo<Void> use(@Valid @RequestBody CouponUseRequest request) {
        try {
            couponService.use(request);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/internal/calculate/coupons/release")
    public ResponseVo<Void> release(@Valid @RequestBody CouponUseRequest request) {
        try {
            couponService.release(request);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }
}
