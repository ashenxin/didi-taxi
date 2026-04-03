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
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@CrossOrigin(origins = "*")
@Validated
@RestController
@RequestMapping("/admin/api/v1/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ResponseVo<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest body) {
        return ResultUtil.success(adminAuthService.login(body.getUsername(), body.getPassword()));
    }

    @GetMapping("/menus")
    public ResponseVo<List<AdminMenuNodeVO>> menus() {
        AdminLoginUser u = (AdminLoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResultUtil.success(adminAuthService.menus(u.userId()));
    }

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
