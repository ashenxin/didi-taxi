package com.sx.adminapi.controller;

import com.sx.adminapi.common.util.ResultUtil;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.capacity.AdminCarVO;
import com.sx.adminapi.model.capacity.AdminCompanyVO;
import com.sx.adminapi.model.capacity.AdminDriverDetailVO;
import com.sx.adminapi.model.capacity.AdminDriverVO;
import com.sx.adminapi.model.capacity.AdminPageVO;
import com.sx.adminapi.service.AdminCapacityService;
import com.sx.adminapi.model.capacity.CompanyCreateBody;
import com.sx.adminapi.model.capacity.CompanyUpdateBody;
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
 * 管理后台运力视图 BFF：公司 / 司机 / 车辆分页（聚合 capacity-service）。
 * 统一前缀：{@code /admin/api/v1/capacity}。
 * 非 SUPER 账号的省、市筛选由 {@link com.sx.adminapi.security.AdminDataScope} 与请求参数合并；越界 403，跨域查看 404。
 */
@RestController
@RequestMapping("/admin/api/v1/capacity")
public class AdminCapacityController {

    private final AdminCapacityService adminCapacityService;

    public AdminCapacityController(AdminCapacityService adminCapacityService) {
        this.adminCapacityService = adminCapacityService;
    }

    /**
     * 运力公司分页；{@code provinceCode}/{@code cityCode} 与当前登录用户数据域合并后下发 capacity。
     * {@code GET /admin/api/v1/capacity/companies?pageNo=&pageSize=&provinceCode=&cityCode=&companyNo=&companyName=}
     */
    @GetMapping("/companies")
    public ResponseVo<AdminPageVO<AdminCompanyVO>> companyPage(@RequestParam(defaultValue = "1") Integer pageNo,
                                                              @RequestParam(defaultValue = "10") Integer pageSize,
                                                              @RequestParam(required = false) String provinceCode,
                                                              @RequestParam(required = false) String cityCode,
                                                              @RequestParam(required = false) String companyNo,
                                                              @RequestParam(required = false) String companyName) {
        return ResultUtil.success(adminCapacityService.companyPage(pageNo, pageSize, provinceCode, cityCode, companyNo, companyName));
    }

    /**
     * 创建「公司 + 车队」。
     * {@code POST /admin/api/v1/capacity/companies}
     */
    @PostMapping("/companies")
    public ResponseVo<AdminCompanyVO> createCompany(@RequestBody CompanyCreateBody body) {
        return ResultUtil.success(adminCapacityService.createCompany(body));
    }

    /**
     * 更新公司名称、车队名称。
     * {@code PUT /admin/api/v1/capacity/companies/{id}}
     */
    @PutMapping("/companies/{id}")
    public ResponseVo<AdminCompanyVO> updateCompany(@PathVariable Long id, @RequestBody CompanyUpdateBody body) {
        return ResultUtil.success(adminCapacityService.updateCompany(id, body));
    }

    /**
     * 逻辑删除运力公司。
     * {@code DELETE /admin/api/v1/capacity/companies/{id}}
     */
    @DeleteMapping("/companies/{id}")
    public ResponseVo<Void> deleteCompany(@PathVariable Long id) {
        adminCapacityService.deleteCompany(id);
        return ResultUtil.success();
    }

    /**
     * 司机分页；可选 {@code provinceCode}/{@code cityCode} 与登录域合并（省管/市管不可扩大查询范围）。
     * {@code GET /admin/api/v1/capacity/drivers?pageNo=&pageSize=&companyId=&name=&phone=&online=&provinceCode=&cityCode=&canAcceptOrder=&auditStatus=}
     */
    @GetMapping("/drivers")
    public ResponseVo<AdminPageVO<AdminDriverVO>> driverPage(@RequestParam(defaultValue = "1") Integer pageNo,
                                                            @RequestParam(defaultValue = "10") Integer pageSize,
                                                            @RequestParam(required = false) Long companyId,
                                                            @RequestParam(required = false) String name,
                                                            @RequestParam(required = false) String phone,
                                                            @RequestParam(required = false) Integer online,
                                                            @RequestParam(required = false) String provinceCode,
                                                            @RequestParam(required = false) String cityCode,
                                                            @RequestParam(required = false) Integer canAcceptOrder,
                                                            @RequestParam(required = false) Integer auditStatus) {
        return ResultUtil.success(adminCapacityService.driverPage(
                pageNo, pageSize, companyId, name, phone, online, provinceCode, cityCode, canAcceptOrder, auditStatus));
    }

    /**
     * 司机档案详情（证件与资质照片 URL、状态枚举等）；越权数据域返回 404。
     * {@code GET /admin/api/v1/capacity/drivers/{driverId}}
     */
    @GetMapping("/drivers/{driverId}")
    public ResponseVo<AdminDriverDetailVO> driverDetail(@PathVariable Long driverId) {
        return ResultUtil.success(adminCapacityService.driverDetail(driverId));
    }

    /**
     * 某司机名下车辆分页；BFF 先校验该司机 {@code cityCode} 落在当前用户数据域内，再调 capacity。
     * {@code GET /admin/api/v1/capacity/drivers/{driverId}/cars?pageNo=&pageSize=}
     */
    @GetMapping("/drivers/{driverId}/cars")
    public ResponseVo<AdminPageVO<AdminCarVO>> carsByDriver(@PathVariable Long driverId,
                                                           @RequestParam(defaultValue = "1") Integer pageNo,
                                                           @RequestParam(defaultValue = "10") Integer pageSize) {
        return ResultUtil.success(adminCapacityService.carsByDriver(driverId, pageNo, pageSize));
    }
}

