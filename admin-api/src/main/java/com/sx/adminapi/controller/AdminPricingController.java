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
}

