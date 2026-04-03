package com.sx.passenger.controller;

import com.sx.passenger.admin.AdminSysAuthService;
import com.sx.passenger.admin.AdminSysMenuService;
import com.sx.passenger.admin.dto.AdminMenuNodeResponse;
import com.sx.passenger.admin.dto.AdminSecurityContextResponse;
import com.sx.passenger.admin.dto.AdminVerifyCredentialsRequest;
import com.sx.passenger.admin.dto.AdminVerifyCredentialsResponse;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台管理 RBAC：对内接口，供 admin-api Feign。
 */
@RestController
@RequestMapping("/api/v1/admin/sys")
public class AdminSysAuthController {

    private final AdminSysAuthService adminSysAuthService;
    private final AdminSysMenuService adminSysMenuService;

    public AdminSysAuthController(AdminSysAuthService adminSysAuthService, AdminSysMenuService adminSysMenuService) {
        this.adminSysAuthService = adminSysAuthService;
        this.adminSysMenuService = adminSysMenuService;
    }

    @PostMapping("/auth/verify-credentials")
    public ResponseEntity<ResponseVo<AdminVerifyCredentialsResponse>> verifyCredentials(
            @Valid @RequestBody AdminVerifyCredentialsRequest body) {
        AdminVerifyCredentialsResponse data =
                adminSysAuthService.verifyCredentials(body.getUsername(), body.getPassword());
        if (data == null) {
            return ResponseEntity.status(401).body(ResultUtil.unauthorized("用户名或密码错误"));
        }
        return ResponseEntity.ok(ResultUtil.success(data));
    }

    @GetMapping("/users/{userId}/security-context")
    public ResponseEntity<ResponseVo<AdminSecurityContextResponse>> securityContext(@PathVariable("userId") long userId) {
        try {
            AdminSecurityContextResponse data = adminSysAuthService.loadSecurityContext(userId);
            if (data == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(ResultUtil.success(data));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(ResultUtil.forbidden("账号数据不一致，请联系管理员"));
        }
    }

    @GetMapping("/users/{userId}/menus")
    public ResponseEntity<ResponseVo<List<AdminMenuNodeResponse>>> userMenus(@PathVariable("userId") long userId) {
        try {
            List<AdminMenuNodeResponse> tree = adminSysMenuService.loadMenuTreeForUser(userId);
            if (tree == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(ResultUtil.success(tree));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(ResultUtil.forbidden("账号数据不一致，请联系管理员"));
        }
    }
}
