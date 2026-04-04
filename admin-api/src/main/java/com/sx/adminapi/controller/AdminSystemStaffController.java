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

    @GetMapping("/{id}")
    public ResponseVo<AdminSystemStaffUserVO> get(@PathVariable long id) {
        return ResultUtil.success(adminSystemStaffService.get(id));
    }

    @PostMapping
    public ResponseVo<AdminSystemStaffUserVO> create(@Valid @RequestBody AdminStaffCreateBody body) {
        return ResultUtil.success(adminSystemStaffService.create(body));
    }

    @PutMapping("/{id}")
    public ResponseVo<AdminSystemStaffUserVO> update(@PathVariable long id, @RequestBody AdminStaffUpdateBody body) {
        return ResultUtil.success(adminSystemStaffService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseVo<Void> delete(@PathVariable long id) {
        adminSystemStaffService.delete(id);
        return ResultUtil.success(null);
    }
}
