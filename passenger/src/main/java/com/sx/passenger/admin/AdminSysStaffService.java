package com.sx.passenger.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sx.passenger.admin.dto.AdminSecurityContextResponse;
import com.sx.passenger.admin.dto.AdminStaffCreateRequest;
import com.sx.passenger.admin.dto.AdminStaffPageResponse;
import com.sx.passenger.admin.dto.AdminStaffUpdateRequest;
import com.sx.passenger.admin.dto.AdminStaffUserVO;
import com.sx.passenger.common.exception.AdminPermissionException;
import com.sx.passenger.common.exception.AdminResourceNotFoundException;
import com.sx.passenger.dao.SysRoleEntityMapper;
import com.sx.passenger.dao.SysUserEntityMapper;
import com.sx.passenger.dao.SysUserRoleEntityMapper;
import com.sx.passenger.model.SysRole;
import com.sx.passenger.model.SysUser;
import com.sx.passenger.model.SysUserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AdminSysStaffService {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private static final String R_SUPER = "SUPER";
    private static final String R_PROVINCE = "PROVINCE_ADMIN";
    private static final String R_CITY = "CITY_OPERATOR";

    private final AdminSysAuthService adminSysAuthService;
    private final SysUserEntityMapper sysUserMapper;
    private final SysUserRoleEntityMapper sysUserRoleMapper;
    private final SysRoleEntityMapper sysRoleMapper;
    /** Redis 与 admin-api 共用缓存键时存在；本地无 Redis 或未启用则为 null */
    private final AdminSecurityContextCacheEvictor securityContextCacheEvictor;

    public AdminSysStaffService(
            AdminSysAuthService adminSysAuthService,
            SysUserEntityMapper sysUserMapper,
            SysUserRoleEntityMapper sysUserRoleMapper,
            SysRoleEntityMapper sysRoleMapper,
            @Autowired(required = false) AdminSecurityContextCacheEvictor securityContextCacheEvictor) {
        this.adminSysAuthService = adminSysAuthService;
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.securityContextCacheEvictor = securityContextCacheEvictor;
    }

    public AdminStaffPageResponse page(
            long callerUserId,
            int pageNo,
            int pageSize,
            String provinceCode,
            String cityCode,
            String username,
            String roleCode) {
        AdminSecurityContextResponse caller = requireStaffCaller(callerUserId);
        boolean superUser = caller.getRoleCodes().contains(R_SUPER);
        boolean provAdmin = caller.getRoleCodes().contains(R_PROVINCE) && !superUser;

        List<String> roleInSql;
        if (provAdmin) {
            provinceCode = caller.getProvinceCode();
            roleInSql = List.of(R_CITY);
        } else {
            if (roleCode != null && !roleCode.isBlank()) {
                if (!R_PROVINCE.equals(roleCode) && !R_CITY.equals(roleCode)) {
                    throw new AdminPermissionException("无效的角色筛选");
                }
                roleInSql = List.of(roleCode);
            } else {
                roleInSql = List.of(R_PROVINCE, R_CITY);
            }
        }

        String codes = roleInSql.stream().map(c -> "'" + c.replace("'", "''") + "'").collect(Collectors.joining(","));
        String inSql = "(SELECT ur.user_id FROM sys_user_role ur INNER JOIN sys_role r ON r.id = ur.role_id AND r.is_deleted = 0 "
                + "WHERE r.code IN (" + codes + "))";

        var wrapper = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getIsDeleted, 0)
                .inSql(SysUser::getId, inSql);
        if (provinceCode != null && !provinceCode.isBlank()) {
            wrapper.eq(SysUser::getProvinceCode, provinceCode);
        }
        if (cityCode != null && !cityCode.isBlank()) {
            wrapper.eq(SysUser::getCityCode, cityCode);
        }
        if (username != null && !username.isBlank()) {
            wrapper.like(SysUser::getUsername, username);
        }
        wrapper.orderByDesc(SysUser::getId);

        Page<SysUser> mp = new Page<>(pageNo, pageSize);
        Page<SysUser> out = sysUserMapper.selectPage(mp, wrapper);

        List<Long> ids = out.getRecords().stream().map(SysUser::getId).toList();
        Map<Long, String> roles = loadPrimaryStaffRole(ids, roleInSql);

        AdminStaffPageResponse resp = new AdminStaffPageResponse();
        resp.setPageNo(pageNo);
        resp.setPageSize(pageSize);
        resp.setTotal(out.getTotal());
        List<AdminStaffUserVO> list = new ArrayList<>();
        for (SysUser u : out.getRecords()) {
            AdminStaffUserVO vo = toVo(u, roles.get(u.getId()));
            if (vo.getRoleCode() != null) {
                list.add(vo);
            }
        }
        resp.setList(list);
        return resp;
    }

    public AdminStaffUserVO getById(long callerUserId, long targetId) {
        requireStaffCaller(callerUserId);
        SysUser u = loadStaffUserOrThrow(targetId);
        String roleCode = requireSingleStaffRole(u.getId());
        assertCallerCanManageTarget(callerUserId, u, roleCode);
        return toVo(u, roleCode);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminStaffUserVO create(long callerUserId, AdminStaffCreateRequest body) {
        AdminSecurityContextResponse caller = requireStaffCaller(callerUserId);
        boolean superUser = caller.getRoleCodes().contains(R_SUPER);

        String rc = body.getRoleCode();
        if (!R_PROVINCE.equals(rc) && !R_CITY.equals(rc)) {
            throw new AdminPermissionException("仅支持创建省管理员或市管理员");
        }
        if (!superUser) {
            if (!R_CITY.equals(rc)) {
                throw new AdminPermissionException("仅超级管理员可创建省管理员");
            }
            if (!Objects.equals(body.getProvinceCode(), caller.getProvinceCode())) {
                throw new AdminPermissionException("市管理员只能建立在本人所辖省份");
            }
        }

        validateScopeFields(rc, body.getProvinceCode(), body.getCityCode());

        SysRole role = sysRoleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, rc).eq(SysRole::getIsDeleted, 0).last("LIMIT 1"));
        if (role == null) {
            throw new AdminPermissionException("角色不存在");
        }

        SysUser u = new SysUser()
                .setUsername(body.getUsername().trim())
                .setPasswordHash(BCRYPT.encode(body.getPassword()))
                .setDisplayName(trimOrNull(body.getDisplayName()))
                .setProvinceCode(body.getProvinceCode())
                .setCityCode(R_CITY.equals(rc) ? body.getCityCode() : null)
                .setTokenVersion(0L)
                .setStatus(1)
                .setIsDeleted(0);
        try {
            sysUserMapper.insert(u);
        } catch (DuplicateKeyException ex) {
            throw new AdminPermissionException("用户名已存在");
        } catch (DataIntegrityViolationException ex) {
            throw new AdminPermissionException("用户名已存在或数据冲突");
        }

        SysUserRole link = new SysUserRole().setUserId(u.getId()).setRoleId(role.getId());
        sysUserRoleMapper.insert(link);
        return toVo(u, rc);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminStaffUserVO update(long callerUserId, long targetId, AdminStaffUpdateRequest body) {
        AdminSecurityContextResponse caller = requireStaffCaller(callerUserId);
        SysUser u = loadStaffUserOrThrow(targetId);
        String roleCode = requireSingleStaffRole(u.getId());
        assertCallerCanManageTarget(callerUserId, u, roleCode);

        boolean superUser = caller.getRoleCodes().contains(R_SUPER);
        boolean provOnly = caller.getRoleCodes().contains(R_PROVINCE) && !superUser;

        if (body.getDisplayName() != null) {
            u.setDisplayName(trimOrNull(body.getDisplayName()));
        }
        if (body.getStatus() != null) {
            u.setStatus(body.getStatus());
        }

        boolean touchGeo = body.getProvinceCode() != null || body.getCityCode() != null;
        if (touchGeo) {
            if (provOnly) {
                if (body.getProvinceCode() != null && !Objects.equals(body.getProvinceCode(), u.getProvinceCode())) {
                    throw new AdminPermissionException("省管理员不可修改目标省份");
                }
                if (!R_CITY.equals(roleCode)) {
                    throw new AdminPermissionException("省管理员仅可调整本市管理员的城市");
                }
                String newCity = body.getCityCode() != null ? body.getCityCode() : u.getCityCode();
                validateScopeFields(R_CITY, u.getProvinceCode(), newCity);
                u.setCityCode(newCity);
            } else {
                String np = body.getProvinceCode() != null ? body.getProvinceCode() : u.getProvinceCode();
                String nc = body.getCityCode() != null ? body.getCityCode() : u.getCityCode();
                validateScopeFields(roleCode, np, nc);
                u.setProvinceCode(np);
                u.setCityCode(R_PROVINCE.equals(roleCode) ? null : nc);
            }
        }

        if (body.getPassword() != null && !body.getPassword().isBlank()) {
            u.setPasswordHash(BCRYPT.encode(body.getPassword()));
            u.setTokenVersion(u.getTokenVersion() == null ? 1L : u.getTokenVersion() + 1);
        }

        sysUserMapper.updateById(u);
        evictAdminSecurityCache(u.getId());
        return getById(callerUserId, targetId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void softDelete(long callerUserId, long targetId) {
        requireStaffCaller(callerUserId);
        SysUser u = loadStaffUserOrThrow(targetId);
        String roleCode = requireSingleStaffRole(u.getId());
        if (Objects.equals(callerUserId, targetId)) {
            throw new AdminPermissionException("不可删除当前登录账号");
        }
        assertCallerCanManageTarget(callerUserId, u, roleCode);
        u.setIsDeleted(1);
        u.setTokenVersion(u.getTokenVersion() == null ? 1L : u.getTokenVersion() + 1);
        sysUserMapper.updateById(u);
        evictAdminSecurityCache(u.getId());
    }

    private void evictAdminSecurityCache(Long userId) {
        if (securityContextCacheEvictor != null && userId != null) {
            securityContextCacheEvictor.evict(userId);
        }
    }

    // --- helpers ---

    private AdminSecurityContextResponse requireStaffCaller(long callerUserId) {
        AdminSecurityContextResponse caller = adminSysAuthService.loadSecurityContext(callerUserId);
        if (caller == null) {
            throw new AdminPermissionException("调用方不存在");
        }
        if (caller.getStatus() == null || caller.getStatus() != 1) {
            throw new AdminPermissionException("调用方状态异常");
        }
        if (caller.getRoleCodes().contains(R_CITY) && !caller.getRoleCodes().contains(R_SUPER)
                && !caller.getRoleCodes().contains(R_PROVINCE)) {
            throw new AdminPermissionException("市管理员无人员管理权限");
        }
        if (!caller.getRoleCodes().contains(R_SUPER) && !caller.getRoleCodes().contains(R_PROVINCE)) {
            throw new AdminPermissionException("无人员管理权限");
        }
        return caller;
    }

    private void assertCallerCanManageTarget(long callerUserId, SysUser target, String targetRole) {
        AdminSecurityContextResponse caller = adminSysAuthService.loadSecurityContext(callerUserId);
        if (caller.getRoleCodes().contains(R_SUPER)) {
            return;
        }
        if (caller.getRoleCodes().contains(R_PROVINCE)) {
            if (!R_CITY.equals(targetRole)) {
                throw new AdminPermissionException("省管理员只能管理市管理员");
            }
            if (!Objects.equals(caller.getProvinceCode(), target.getProvinceCode())) {
                throw new AdminPermissionException("只能管理本省市管理员");
            }
            return;
        }
        throw new AdminPermissionException("无人员管理权限");
    }

    private SysUser loadStaffUserOrThrow(long id) {
        SysUser u = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getId, id).eq(SysUser::getIsDeleted, 0).last("LIMIT 1"));
        if (u == null) {
            throw new AdminResourceNotFoundException("用户不存在");
        }
        return u;
    }

    /** 目标须唯一绑定 PROVINCE_ADMIN 或 CITY_OPERATOR */
    private String requireSingleStaffRole(long userId) {
        List<SysUserRole> urs = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        if (urs.isEmpty()) {
            throw new AdminResourceNotFoundException("用户不存在");
        }
        List<Long> roleIds = urs.stream().map(SysUserRole::getRoleId).distinct().toList();
        List<SysRole> roles = sysRoleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery().in(SysRole::getId, roleIds).eq(SysRole::getIsDeleted, 0));
        if (roles.stream().anyMatch(r -> R_SUPER.equals(r.getCode()))) {
            throw new AdminPermissionException("不可管理超级管理员");
        }
        List<String> staffRoles = roles.stream()
                .map(SysRole::getCode)
                .filter(c -> R_PROVINCE.equals(c) || R_CITY.equals(c))
                .distinct()
                .toList();
        if (staffRoles.isEmpty()) {
            throw new AdminPermissionException("不可管理该用户");
        }
        if (staffRoles.size() > 1) {
            throw new AdminPermissionException("用户角色数据异常，请联系超级管理员");
        }
        return staffRoles.getFirst();
    }

    private Map<Long, String> loadPrimaryStaffRole(List<Long> userIds, List<String> allowedRoleCodes) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        List<SysUserRole> urs = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getUserId, userIds));
        if (urs.isEmpty()) {
            return out;
        }
        List<Long> roleIds = urs.stream().map(SysUserRole::getRoleId).distinct().toList();
        List<SysRole> roleRows = sysRoleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery().in(SysRole::getId, roleIds).eq(SysRole::getIsDeleted, 0));
        Map<Long, String> idToCode = roleRows.stream().collect(Collectors.toMap(SysRole::getId, SysRole::getCode));
        for (SysUserRole ur : urs) {
            String code = idToCode.get(ur.getRoleId());
            if (code != null && allowedRoleCodes.contains(code)) {
                out.putIfAbsent(ur.getUserId(), code);
            }
        }
        return out;
    }

    private static AdminStaffUserVO toVo(SysUser u, String roleCode) {
        AdminStaffUserVO vo = new AdminStaffUserVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setDisplayName(u.getDisplayName());
        vo.setProvinceCode(u.getProvinceCode());
        vo.setCityCode(u.getCityCode());
        vo.setStatus(u.getStatus());
        vo.setRoleCode(roleCode);
        return vo;
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static void validateScopeFields(String roleCode, String provinceCode, String cityCode) {
        if (provinceCode == null || provinceCode.isBlank()) {
            throw new AdminPermissionException("省份编码不能为空");
        }
        if (R_PROVINCE.equals(roleCode)) {
            if (cityCode != null && !cityCode.isBlank()) {
                throw new AdminPermissionException("省管理员不能绑定城市编码");
            }
            return;
        }
        if (cityCode == null || cityCode.isBlank()) {
            throw new AdminPermissionException("市管理员必须填写城市编码");
        }
        if (!cityBelongsToProvince(cityCode.trim(), provinceCode.trim())) {
            throw new AdminPermissionException("城市须隶属于所选省份（行政区划编码前缀校验）");
        }
    }

    /**
     * 宽松校验：市编码前两位与省编码前两位一致（兼容常见 6 位行政区划码）。
     */
    private static boolean cityBelongsToProvince(String cityCode, String provinceCode) {
        if (cityCode.length() < 2 || provinceCode.length() < 2) {
            return false;
        }
        return cityCode.startsWith(provinceCode.substring(0, 2));
    }
}
