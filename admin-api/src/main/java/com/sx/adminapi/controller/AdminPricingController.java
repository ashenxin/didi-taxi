package com.sx.adminapi.controller;

import com.sx.adminapi.common.util.ResultUtil;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.capacity.AdminPageVO;
import com.sx.adminapi.model.pricing.AdminFareRuleVO;
import com.sx.adminapi.model.pricing.FareRuleUpsertBody;
import com.sx.adminapi.service.AdminPricingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理后台：计价规则 BFF，转发 {@code calculate-service} 的 fare_rule CRUD。
 * 统一前缀：{@code /admin/api/v1/pricing/fare-rules}。
 * 列表筛选与写操作的省、市受 {@link com.sx.adminapi.security.AdminDataScope} 约束（越界 403，跨域读写 404）。
 */
@RestController
@RequestMapping("/admin/api/v1/pricing/fare-rules")
public class AdminPricingController {

    private final AdminPricingService adminPricingService;

    public AdminPricingController(AdminPricingService adminPricingService) {
        this.adminPricingService = adminPricingService;
    }

    /**
     * 计价规则分页列表；{@code provinceCode}/{@code cityCode} 与登录域合并。
     * {@code GET /admin/api/v1/pricing/fare-rules?pageNo=&pageSize=&companyId=&provinceCode=&cityCode=&productCode=&ruleName=&active=}
     */
    @GetMapping
    public ResponseVo<AdminPageVO<AdminFareRuleVO>> page(@RequestParam(defaultValue = "1") Integer pageNo,
                                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                                        @RequestParam(required = false) Long companyId,
                                                        @RequestParam(required = false) String provinceCode,
                                                        @RequestParam(required = false) String cityCode,
                                                        @RequestParam(required = false) String productCode,
                                                        @RequestParam(required = false) String ruleName,
                                                        @RequestParam(required = false) Integer active) {
        return ResultUtil.success(adminPricingService.page(pageNo, pageSize, companyId, provinceCode, cityCode, productCode, ruleName, active));
    }

    /**
     * 计价规则详情；规则不在当前用户数据域内时 404。
     * {@code GET /admin/api/v1/pricing/fare-rules/{id}}
     */
    @GetMapping("/{id}")
    public ResponseVo<AdminFareRuleVO> detail(@PathVariable Long id) {
        return ResultUtil.success(adminPricingService.detail(id));
    }

    /**
     * 新建计价规则；body 中省、市会被裁剪到当前账号可写范围。
     * {@code POST /admin/api/v1/pricing/fare-rules}
     */
    @PostMapping
    public ResponseVo<Long> create(@Valid @RequestBody FareRuleUpsertBody body) {
        return ResultUtil.success(adminPricingService.create(body));
    }

    /**
     * 更新计价规则；先校验原规则可读域，再对 body 做省、市锁定。
     * {@code PUT /admin/api/v1/pricing/fare-rules/{id}}
     */
    @PutMapping("/{id}")
    public ResponseVo<Void> update(@PathVariable Long id, @Valid @RequestBody FareRuleUpsertBody body) {
        adminPricingService.update(id, body);
        return ResultUtil.success(null);
    }

    /**
     * 逻辑删除计价规则；仅允许删除当前数据域内规则。
     * {@code DELETE /admin/api/v1/pricing/fare-rules/{id}}
     */
    @DeleteMapping("/{id}")
    public ResponseVo<Void> delete(@PathVariable Long id) {
        adminPricingService.delete(id);
        return ResultUtil.success(null);
    }

    /**
     * 查询指定计价规则关联的优惠券模板；规则和模板均受当前管理员数据域约束。
     * {@code GET /admin/api/v1/pricing/fare-rules/{id}/coupons?status=&pageNo=&pageSize=}
     */
    @GetMapping("/{id}/coupons")
    public ResponseVo<Map<String, Object>> couponTemplates(@PathVariable Long id,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "1") Integer pageNo,
                                                           @RequestParam(defaultValue = "20") Integer pageSize) {
        return ResultUtil.success(adminPricingService.couponTemplates(id, status, pageNo, pageSize));
    }

    /**
     * 为指定计价规则创建优惠券模板，模板的公司、城市和产品线继承该规则的归属。
     * {@code POST /admin/api/v1/pricing/fare-rules/{id}/coupons}
     */
    @PostMapping("/{id}/coupons")
    public ResponseVo<Map<String, Object>> createCouponTemplate(@PathVariable Long id,
                                                                @RequestBody Map<String, Object> body) {
        return ResultUtil.success(adminPricingService.createCouponTemplate(id, body));
    }

    /**
     * 更新指定计价规则下的优惠券模板；不允许跨规则或跨数据域修改。
     * {@code PUT /admin/api/v1/pricing/fare-rules/{id}/coupons/{templateId}}
     */
    @PutMapping("/{id}/coupons/{templateId}")
    public ResponseVo<Map<String, Object>> updateCouponTemplate(@PathVariable Long id,
                                                                @PathVariable Long templateId,
                                                                @RequestBody Map<String, Object> body) {
        return ResultUtil.success(adminPricingService.updateCouponTemplate(id, templateId, body));
    }

    /**
     * 发布优惠券模板，使其进入可领取状态。
     * {@code POST /admin/api/v1/pricing/fare-rules/{id}/coupons/{templateId}/publish}
     */
    @PostMapping("/{id}/coupons/{templateId}/publish")
    public ResponseVo<Map<String, Object>> publishCouponTemplate(@PathVariable Long id,
                                                                 @PathVariable Long templateId) {
        return ResultUtil.success(adminPricingService.publishCouponTemplate(id, templateId));
    }

    /**
     * 下线优惠券模板，停止后续领取但保留历史券与核销记录。
     * {@code POST /admin/api/v1/pricing/fare-rules/{id}/coupons/{templateId}/offline}
     */
    @PostMapping("/{id}/coupons/{templateId}/offline")
    public ResponseVo<Map<String, Object>> offlineCouponTemplate(@PathVariable Long id,
                                                                 @PathVariable Long templateId) {
        return ResultUtil.success(adminPricingService.offlineCouponTemplate(id, templateId));
    }
}
