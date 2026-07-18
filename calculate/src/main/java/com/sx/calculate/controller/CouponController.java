package com.sx.calculate.controller;

import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.model.dto.CouponLockRequest;
import com.sx.calculate.model.dto.CouponLockResult;
import com.sx.calculate.model.dto.CouponPageVO;
import com.sx.calculate.model.dto.CouponClaimRequest;
import com.sx.calculate.model.dto.CouponClaimResult;
import com.sx.calculate.model.dto.CouponInvalidateRequest;
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

/**
 * 优惠券核心接口：乘客券包、可用券筛选、券模板管理以及结算阶段的锁定、核销和释放。
 * 对外接口前缀为 {@code /api/v1/coupons}，结算内部接口前缀为 {@code /internal/calculate/coupons}。
 */
@RestController
public class CouponController {
    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * 分页查询指定乘客的优惠券，可按券状态筛选。
     * {@code GET /api/v1/coupons?passengerId=&status=&pageNo=&pageSize=}
     */
    @GetMapping("/api/v1/coupons")
    public ResponseVo<CouponPageVO> page(@RequestParam Long passengerId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                         @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return ResultUtil.success(couponService.page(passengerId, status, pageNo, pageSize));
    }

    /**
     * 根据运力公司、订单金额、城市和产品线筛选乘客当前可用优惠券。
     * {@code GET /api/v1/coupons/available?passengerId=&companyId=&finalAmount=&cityCode=&productCode=}
     */
    @GetMapping("/api/v1/coupons/available")
    public ResponseVo<List<CouponVO>> available(@RequestParam Long passengerId,
                                                @RequestParam Long companyId,
                                                @RequestParam BigDecimal finalAmount,
                                                @RequestParam String cityCode,
                                                @RequestParam String productCode) {
        return ResultUtil.success(couponService.listAvailable(passengerId, companyId, finalAmount, cityCode, productCode));
    }

    /**
     * 查询乘客当前可领取的券模板，可携带经过保护的领取身份条件。
     * {@code GET /api/v1/coupons/claimable?passengerId=&claimIdentityType=&claimIdentityHash=}
     */
    @GetMapping("/api/v1/coupons/claimable")
    public ResponseVo<List<CouponTemplateVO>> claimable(@RequestParam Long passengerId,
                                                        @RequestParam(required = false) String claimIdentityType,
                                                        @RequestParam(required = false) String claimIdentityHash) {
        return ResultUtil.success(couponService.claimable(passengerId, claimIdentityType, claimIdentityHash));
    }

    /**
     * 一次领取当前满足条件的全部券模板，重复领取按模板规则幂等处理。
     * {@code POST /api/v1/coupons/claim-all?passengerId=}
     */
    @PostMapping("/api/v1/coupons/claim-all")
    public ResponseVo<CouponClaimResult> claimAll(@RequestParam Long passengerId,
                                                  @RequestBody(required = false) CouponClaimRequest request) {
        return ResultUtil.success(couponService.claimAll(passengerId,
                request == null ? null : request.getClaimIdentityType(),
                request == null ? null : request.getClaimIdentityHash()));
    }

    /**
     * 按模板 ID 列表领取指定优惠券。
     * {@code POST /api/v1/coupons/claim?passengerId=}
     */
    @PostMapping("/api/v1/coupons/claim")
    public ResponseVo<CouponClaimResult> claim(@RequestParam Long passengerId,
                                               @RequestBody CouponClaimRequest request) {
        return ResultUtil.success(couponService.claimSelected(passengerId,
                request == null ? null : request.getTemplateIds(),
                request == null ? null : request.getClaimIdentityType(),
                request == null ? null : request.getClaimIdentityHash()));
    }

    /**
     * 分页查询优惠券模板，支持按公司、城市、产品线和状态筛选。
     * {@code GET /api/v1/coupons/templates?companyId=&cityCode=&productCode=&status=&pageNo=&pageSize=}
     */
    @GetMapping("/api/v1/coupons/templates")
    public ResponseVo<CouponTemplatePageVO> templates(@RequestParam(required = false) Long companyId,
                                                      @RequestParam(required = false) String cityCode,
                                                      @RequestParam(required = false) String productCode,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                                      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return ResultUtil.success(couponService.pageTemplates(companyId, cityCode, productCode, status, pageNo, pageSize));
    }

    /**
     * 创建优惠券模板；新模板需按后续发布接口进入可领取状态。
     * {@code POST /api/v1/coupons/templates}
     */
    @PostMapping("/api/v1/coupons/templates")
    public ResponseVo<CouponTemplateVO> createTemplate(@Valid @RequestBody CouponTemplateUpsertRequest request) {
        try {
            return ResultUtil.success(couponService.createTemplate(request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 更新指定优惠券模板的规则和展示信息。
     * {@code PUT /api/v1/coupons/templates/{templateId}}
     */
    @PutMapping("/api/v1/coupons/templates/{templateId}")
    public ResponseVo<CouponTemplateVO> updateTemplate(@PathVariable Long templateId,
                                                       @Valid @RequestBody CouponTemplateUpsertRequest request) {
        try {
            return ResultUtil.success(couponService.updateTemplate(templateId, request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 发布优惠券模板，使其进入可领取状态，并记录可选操作人。
     * {@code POST /api/v1/coupons/templates/{templateId}/publish?operatorId=}
     */
    @PostMapping("/api/v1/coupons/templates/{templateId}/publish")
    public ResponseVo<CouponTemplateVO> publishTemplate(@PathVariable Long templateId,
                                                        @RequestParam(required = false) Long operatorId) {
        try {
            return ResultUtil.success(couponService.publishTemplate(templateId, operatorId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 下线优惠券模板，停止后续领取并记录可选操作人。
     * {@code POST /api/v1/coupons/templates/{templateId}/offline?operatorId=}
     */
    @PostMapping("/api/v1/coupons/templates/{templateId}/offline")
    public ResponseVo<CouponTemplateVO> offlineTemplate(@PathVariable Long templateId,
                                                        @RequestParam(required = false) Long operatorId) {
        try {
            return ResultUtil.success(couponService.offlineTemplate(templateId, operatorId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 结算内部接口：为订单选择并锁定优惠券，防止同一张券并发用于多个订单。
     * {@code POST /internal/calculate/coupons/lock}
     */
    @PostMapping("/internal/calculate/coupons/lock")
    public ResponseVo<CouponLockResult> lock(@Valid @RequestBody CouponLockRequest request) {
        try {
            return ResultUtil.success(couponService.lock(request));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 结算内部接口：支付完成后核销已锁定的优惠券。
     * {@code POST /internal/calculate/coupons/use}
     */
    @PostMapping("/internal/calculate/coupons/use")
    public ResponseVo<Void> use(@Valid @RequestBody CouponUseRequest request) {
        try {
            couponService.use(request);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 结算内部接口：结算或支付失败时释放订单已锁定的优惠券。
     * {@code POST /internal/calculate/coupons/release}
     */
    @PostMapping("/internal/calculate/coupons/release")
    public ResponseVo<Void> release(@Valid @RequestBody CouponUseRequest request) {
        try {
            couponService.release(request);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 注销预检内部接口：判断乘客是否仍有处于锁定状态的优惠券。
     * {@code GET /internal/calculate/coupons/locked-exists?passengerId=}
     */
    @GetMapping("/internal/calculate/coupons/locked-exists")
    public ResponseVo<Boolean> lockedExists(@RequestParam Long passengerId) {
        try {
            if (passengerId == null || passengerId <= 0) {
                return ResultUtil.requestError("passengerId不能为空");
            }
            couponService.assertNoLockedCoupons(passengerId);
            return ResultUtil.success(false);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.success(true);
        }
    }

    /**
     * 注销清理内部接口：批量作废指定乘客尚未使用的优惠券，并返回处理数量。
     * {@code POST /internal/calculate/coupons/invalidate-by-passenger}
     */
    @PostMapping("/internal/calculate/coupons/invalidate-by-passenger")
    public ResponseVo<Integer> invalidateByPassenger(@RequestBody CouponInvalidateRequest request) {
        try {
            if (request == null || request.getPassengerId() == null || request.getPassengerId() <= 0) {
                return ResultUtil.requestError("passengerId不能为空");
            }
            return ResultUtil.success(couponService.invalidateByPassenger(
                    request.getPassengerId(),
                    request.getReason()));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }
}
