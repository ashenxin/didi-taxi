package com.sx.adminapi.controller;

import com.sx.adminapi.common.util.ResultUtil;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.auth.AdminLoginRequest;
import com.sx.adminapi.model.auth.AdminLoginResponse;
import com.sx.adminapi.model.auth.AdminMenuNodeVO;
import com.sx.adminapi.model.auth.AdminUserVO;
import com.sx.adminapi.security.AdminLoginUser;
import com.sx.adminapi.service.AdminAuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台认证与会话：登录签发 JWT、菜单树、当前登录用户概要。
 * <p>统一前缀：{@code /admin/api/v1/auth}；需登录的接口依赖 Spring Security 上下文中的 {@link AdminLoginUser}。</p>
 */
@Validated
@RestController
@RequestMapping("/admin/api/v1/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    /**
     * 用户名密码登录，返回访问令牌及基础账号信息。
     * <p>{@code POST /admin/api/v1/auth/login}</p>
     */
    @PostMapping("/login")
    public ResponseVo<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest body) {
        return ResultUtil.success(adminAuthService.login(body.getUsername(), body.getPassword()));
    }

    /**
     * 当前登录用户可见的后台菜单树（按角色过滤）。
     * <p>{@code GET /admin/api/v1/auth/menus}</p>
     */
    @GetMapping("/menus")
    public ResponseVo<List<AdminMenuNodeVO>> menus() {
        AdminLoginUser u = (AdminLoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResultUtil.success(adminAuthService.menus(u.userId()));
    }

    /**
     * 当前登录用户资料（用户名、展示名、角色、省市数据域、token 版本等）。
     * <p>{@code GET /admin/api/v1/auth/me}</p>
     */
    @GetMapping("/me")
    public ResponseVo<AdminUserVO> me() {
        AdminLoginUser u = (AdminLoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        List<String> roles = u.roleCodes() == null ? List.of() : List.copyOf(u.roleCodes());
        AdminUserVO vo = new AdminUserVO(
                u.userId(),
                u.username(),
                u.displayName(),
                roles,
                u.provinceCode(),
                u.cityCode(),
                u.tokenVersion());
        return ResultUtil.success(vo);
    }
}
