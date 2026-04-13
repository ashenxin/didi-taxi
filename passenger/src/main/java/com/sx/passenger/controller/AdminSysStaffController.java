package com.sx.passenger.controller;

import com.sx.passenger.admin.AdminSysStaffService;
import com.sx.passenger.admin.dto.AdminStaffCreateRequest;
import com.sx.passenger.admin.dto.AdminStaffPageResponse;
import com.sx.passenger.admin.dto.AdminStaffUpdateRequest;
import com.sx.passenger.admin.dto.AdminStaffUserVO;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 省/市管理员 CRUD（对内）；调用方身份通过 {@code X-Caller-User-Id} 传递，服务内再校验数据范围。
 * 统一前缀：{@code /api/v1/admin/sys/admin-users}。
 */
@RestController
@RequestMapping("/api/v1/admin/sys/admin-users")
public class AdminSysStaffController {

    private final AdminSysStaffService adminSysStaffService;

    public AdminSysStaffController(AdminSysStaffService adminSysStaffService) {
        this.adminSysStaffService = adminSysStaffService;
    }

    /**
     * 管理员账号分页。
     * {@code GET /api/v1/admin/sys/admin-users?pageNo=&pageSize=&provinceCode=&cityCode=&username=&roleCode=}
     */
    @GetMapping
    public ResponseEntity<ResponseVo<AdminStaffPageResponse>> page(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String roleCode) {
        AdminStaffPageResponse data = adminSysStaffService.page(
                callerUserId, pageNo, pageSize, provinceCode, cityCode, username, roleCode);
        return ResponseEntity.ok(ResultUtil.success(data));
    }

    /**
     * 管理员账号详情。
     * {@code GET /api/v1/admin/sys/admin-users/{id}}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ResponseVo<AdminStaffUserVO>> get(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @PathVariable("id") long id) {
        AdminStaffUserVO data = adminSysStaffService.getById(callerUserId, id);
        return ResponseEntity.ok(ResultUtil.success(data));
    }

    /**
     * 新建管理员账号。
     * {@code POST /api/v1/admin/sys/admin-users}
     */
    @PostMapping
    public ResponseEntity<ResponseVo<AdminStaffUserVO>> create(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @Valid @RequestBody AdminStaffCreateRequest body) {
        AdminStaffUserVO data = adminSysStaffService.create(callerUserId, body);
        return ResponseEntity.ok(ResultUtil.success(data));
    }

    /**
     * 更新管理员账号。
     * {@code PUT /api/v1/admin/sys/admin-users/{id}}
     */
    @PutMapping("/{id}")
    public ResponseEntity<ResponseVo<AdminStaffUserVO>> update(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @PathVariable("id") long id,
            @RequestBody AdminStaffUpdateRequest body) {
        AdminStaffUserVO data = adminSysStaffService.update(callerUserId, id, body);
        return ResponseEntity.ok(ResultUtil.success(data));
    }

    /**
     * 软删管理员账号。
     * {@code DELETE /api/v1/admin/sys/admin-users/{id}}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseVo<Void>> delete(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @PathVariable("id") long id) {
        adminSysStaffService.softDelete(callerUserId, id);
        return ResponseEntity.ok(ResultUtil.success(null));
    }
}
