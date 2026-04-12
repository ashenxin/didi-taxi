package com.sx.adminapi.controller;

import com.sx.adminapi.common.util.ResultUtil;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.capacity.AdminPageVO;
import com.sx.adminapi.model.system.AdminStaffCreateBody;
import com.sx.adminapi.model.system.AdminStaffUpdateBody;
import com.sx.adminapi.model.system.AdminSystemStaffUserVO;
import com.sx.adminapi.service.AdminSystemStaffService;
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
 * 管理员维护：超级管理员可管省/市管理员；省管理员仅可管本省市管理员；市管理员无此接口权限。
 * <p>统一前缀：{@code /admin/api/v1/system/admin-users}。</p>
 */
@RestController
@RequestMapping("/admin/api/v1/system/admin-users")
public class AdminSystemStaffController {

    private final AdminSystemStaffService adminSystemStaffService;

    public AdminSystemStaffController(AdminSystemStaffService adminSystemStaffService) {
        this.adminSystemStaffService = adminSystemStaffService;
    }

    /**
     * 省/市管理员账号分页列表。
     * <p>{@code GET /admin/api/v1/system/admin-users?pageNo=&pageSize=&provinceCode=&cityCode=&username=&roleCode=}</p>
     */
    @GetMapping
    public ResponseVo<AdminPageVO<AdminSystemStaffUserVO>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String roleCode) {
        return ResultUtil.success(adminSystemStaffService.page(pageNo, pageSize, provinceCode, cityCode, username, roleCode));
    }

    /**
     * 管理员账号详情。
     * <p>{@code GET /admin/api/v1/system/admin-users/{id}}</p>
     */
    @GetMapping("/{id}")
    public ResponseVo<AdminSystemStaffUserVO> get(@PathVariable long id) {
        return ResultUtil.success(adminSystemStaffService.get(id));
    }

    /**
     * 新建省/市管理员账号（权限受调用者角色约束）。
     * <p>{@code POST /admin/api/v1/system/admin-users}</p>
     */
    @PostMapping
    public ResponseVo<AdminSystemStaffUserVO> create(@Valid @RequestBody AdminStaffCreateBody body) {
        return ResultUtil.success(adminSystemStaffService.create(body));
    }

    /**
     * 更新管理员账号信息。
     * <p>{@code PUT /admin/api/v1/system/admin-users/{id}}</p>
     */
    @PutMapping("/{id}")
    public ResponseVo<AdminSystemStaffUserVO> update(@PathVariable long id, @RequestBody AdminStaffUpdateBody body) {
        return ResultUtil.success(adminSystemStaffService.update(id, body));
    }

    /**
     * 逻辑删除管理员账号。
     * <p>{@code DELETE /admin/api/v1/system/admin-users/{id}}</p>
     */
    @DeleteMapping("/{id}")
    public ResponseVo<Void> delete(@PathVariable long id) {
        adminSystemStaffService.delete(id);
        return ResultUtil.success(null);
    }
}
