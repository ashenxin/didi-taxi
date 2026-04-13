package com.sx.passenger.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.admin.dto.AdminSecurityContextResponse;
import com.sx.passenger.admin.dto.AdminVerifyCredentialsResponse;
import com.sx.passenger.dao.SysRoleEntityMapper;
import com.sx.passenger.dao.SysUserEntityMapper;
import com.sx.passenger.dao.SysUserRoleEntityMapper;
import com.sx.passenger.model.SysRole;
import com.sx.passenger.model.SysUser;
import com.sx.passenger.model.SysUserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AdminSysAuthService {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private final SysUserEntityMapper sysUserMapper;
    private final SysUserRoleEntityMapper sysUserRoleMapper;
    private final SysRoleEntityMapper sysRoleMapper;

    public AdminSysAuthService(
            SysUserEntityMapper sysUserMapper,
            SysUserRoleEntityMapper sysUserRoleMapper,
            SysRoleEntityMapper sysRoleMapper) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMapper = sysRoleMapper;
    }

    public AdminVerifyCredentialsResponse verifyCredentials(String username, String password) {
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getUsername, username)
                        .eq(SysUser::getIsDeleted, 0)
                        .last("LIMIT 1"));
        if (user == null || user.getPasswordHash() == null || !BCRYPT.matches(password, user.getPasswordHash())) {
            log.warn("admin login failed username={}", username);
            return null;
        }
        log.info("admin login verify ok userId={} username={}", user.getId(), user.getUsername());
        return new AdminVerifyCredentialsResponse(user.getId(), user.getTokenVersion(), user.getStatus());
    }

    public AdminSecurityContextResponse loadSecurityContext(long userId) {
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getId, userId)
                        .eq(SysUser::getIsDeleted, 0)
                        .last("LIMIT 1"));
        if (user == null) {
            return null;
        }
        List<String> roleCodes = listActiveRoleCodes(userId);
        if (!roleCodes.isEmpty()) {
            validateRoleScopeOrThrow(user, roleCodes);
        }

        AdminSecurityContextResponse ctx = new AdminSecurityContextResponse();
        ctx.setUserId(user.getId());
        ctx.setUsername(user.getUsername());
        ctx.setDisplayName(user.getDisplayName());
        ctx.setProvinceCode(user.getProvinceCode());
        ctx.setCityCode(user.getCityCode());
        ctx.setTokenVersion(user.getTokenVersion());
        ctx.setStatus(user.getStatus());
        ctx.setRoleCodes(roleCodes);
        ctx.setMenuIds(List.of());
        return ctx;
    }

    private List<String> listActiveRoleCodes(long userId) {
        List<SysUserRole> urs = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        if (urs.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = urs.stream().map(SysUserRole::getRoleId).distinct().toList();
        List<SysRole> roles = sysRoleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery()
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getIsDeleted, 0)
                        .eq(SysRole::getStatus, 1));
        return roles.stream().map(SysRole::getCode).sorted().collect(Collectors.toList());
    }

    /**
     * 与设计文档一致：超管/省管/市员与省市区字段匹配；不一致时拒绝登录后的上下文加载。
     */
    /** 供菜单等模块复用：与 {@link #loadSecurityContext} 相同的省市区与角色一致性校验。 */
    public void validateUserScopeForMenus(SysUser user, List<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return;
        }
        validateRoleScopeOrThrow(user, roleCodes);
    }

    private void validateRoleScopeOrThrow(SysUser user, List<String> roleCodes) {
        boolean superAdmin = roleCodes.contains("SUPER");
        boolean provinceAdmin = roleCodes.contains("PROVINCE_ADMIN");
        boolean cityOp = roleCodes.contains("CITY_OPERATOR");
        if (superAdmin) {
            if (user.getProvinceCode() != null || user.getCityCode() != null) {
                throw new IllegalStateException("SUPER 用户须 province_code、city_code 均为空");
            }
            return;
        }
        if (provinceAdmin && !cityOp) {
            if (user.getProvinceCode() == null || user.getCityCode() != null) {
                throw new IllegalStateException("PROVINCE_ADMIN 须 province_code 非空且 city_code 为空");
            }
            return;
        }
        if (cityOp && !provinceAdmin) {
            if (user.getProvinceCode() == null || user.getCityCode() == null) {
                throw new IllegalStateException("CITY_OPERATOR 须 province_code、city_code 均非空");
            }
            return;
        }
        if (provinceAdmin && cityOp) {
            throw new IllegalStateException("不可同时担任 PROVINCE_ADMIN 与 CITY_OPERATOR");
        }
    }
}
