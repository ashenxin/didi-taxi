package com.sx.passenger.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.admin.dto.AdminMenuNodeResponse;
import com.sx.passenger.dao.SysMenuEntityMapper;
import com.sx.passenger.dao.SysRoleEntityMapper;
import com.sx.passenger.dao.SysRoleMenuEntityMapper;
import com.sx.passenger.dao.SysUserEntityMapper;
import com.sx.passenger.dao.SysUserRoleEntityMapper;
import com.sx.passenger.model.SysMenu;
import com.sx.passenger.model.SysRole;
import com.sx.passenger.model.SysRoleMenu;
import com.sx.passenger.model.SysUser;
import com.sx.passenger.model.SysUserRole;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminSysMenuService {

    private final SysUserEntityMapper sysUserMapper;
    private final SysUserRoleEntityMapper sysUserRoleMapper;
    private final SysRoleEntityMapper sysRoleMapper;
    private final SysRoleMenuEntityMapper sysRoleMenuMapper;
    private final SysMenuEntityMapper sysMenuMapper;
    private final AdminSysAuthService adminSysAuthService;

    public AdminSysMenuService(
            SysUserEntityMapper sysUserMapper,
            SysUserRoleEntityMapper sysUserRoleMapper,
            SysRoleEntityMapper sysRoleMapper,
            SysRoleMenuEntityMapper sysRoleMenuMapper,
            SysMenuEntityMapper sysMenuMapper,
            AdminSysAuthService adminSysAuthService) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysRoleMenuMapper = sysRoleMenuMapper;
        this.sysMenuMapper = sysMenuMapper;
        this.adminSysAuthService = adminSysAuthService;
    }

    /**
     * <ul>
     *   <li>SUPER：全部有效菜单</li>
     *   <li>PROVINCE_ADMIN：有效菜单全集（与超管同屏；数据范围由各接口按省裁剪）</li>
     *   <li>CITY_OPERATOR：除 {@code /system} 外的有效菜单（不可管理同级后台用户）</li>
     *   <li>其它角色：{@code sys_role_menu} 并集</li>
     * </ul>
     */
    public List<AdminMenuNodeResponse> loadMenuTreeForUser(long userId) {
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
            adminSysAuthService.validateUserScopeForMenus(user, roleCodes);
        }

        Set<Long> menuIds = resolveMenuIdsForRoles(userId, roleCodes);
        if (menuIds.isEmpty()) {
            return List.of();
        }

        Set<Long> closure = expandWithAncestors(menuIds);
        List<SysMenu> fullRows = sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .in(SysMenu::getId, closure)
                        .eq(SysMenu::getIsDeleted, 0)
                        .eq(SysMenu::getStatus, 1));
        fullRows.sort(Comparator.comparing(SysMenu::getSort, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SysMenu::getId));

        Map<Long, AdminMenuNodeResponse> nodes = new LinkedHashMap<>();
        for (SysMenu m : fullRows) {
            nodes.put(m.getId(), toNode(m));
        }
        List<AdminMenuNodeResponse> roots = new ArrayList<>();
        for (SysMenu m : fullRows) {
            AdminMenuNodeResponse n = nodes.get(m.getId());
            if (m.getParentId() == null) {
                roots.add(n);
            } else {
                AdminMenuNodeResponse p = nodes.get(m.getParentId());
                if (p != null) {
                    p.getChildren().add(n);
                } else {
                    roots.add(n);
                }
            }
        }
        for (AdminMenuNodeResponse r : roots) {
            sortChildrenRecursive(r);
        }
        return roots;
    }

    private List<SysMenu> listAllActiveMenus() {
        return sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .eq(SysMenu::getIsDeleted, 0)
                        .eq(SysMenu::getStatus, 1));
    }

    /**
     * 市管菜单：排除 path 以 {@code /system} 开头的项（及无 path 时保守保留给自定义菜单场景）。
     */
    private static boolean cityOperatorCanSeeMenu(SysMenu m) {
        String p = m.getPath();
        if (p == null || p.isBlank()) {
            return true;
        }
        return !p.startsWith("/system");
    }

    private Set<Long> resolveMenuIdsForRoles(long userId, List<String> roleCodes) {
        if (roleCodes.contains("SUPER")) {
            return listAllActiveMenus().stream()
                    .map(SysMenu::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (roleCodes.contains("PROVINCE_ADMIN")) {
            return listAllActiveMenus().stream()
                    .map(SysMenu::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (roleCodes.contains("CITY_OPERATOR")) {
            return listAllActiveMenus().stream()
                    .filter(AdminSysMenuService::cityOperatorCanSeeMenu)
                    .map(SysMenu::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        List<Long> roleIds = resolveActiveRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<SysRoleMenu> rms = sysRoleMenuMapper.selectList(
                Wrappers.<SysRoleMenu>lambdaQuery().in(SysRoleMenu::getRoleId, roleIds));
        return rms.stream().map(SysRoleMenu::getMenuId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Long> expandWithAncestors(Set<Long> menuIds) {
        Set<Long> closure = new LinkedHashSet<>(menuIds);
        Deque<Long> q = new ArrayDeque<>(menuIds);
        while (!q.isEmpty()) {
            Long id = q.poll();
            SysMenu m = sysMenuMapper.selectById(id);
            if (m != null && m.getParentId() != null && closure.add(m.getParentId())) {
                q.add(m.getParentId());
            }
        }
        return closure;
    }

    private void sortChildrenRecursive(AdminMenuNodeResponse node) {
        node.getChildren().sort(Comparator.comparingInt(AdminMenuNodeResponse::getSort).thenComparingLong(AdminMenuNodeResponse::getId));
        for (AdminMenuNodeResponse c : node.getChildren()) {
            sortChildrenRecursive(c);
        }
    }

    private static AdminMenuNodeResponse toNode(SysMenu m) {
        AdminMenuNodeResponse n = new AdminMenuNodeResponse();
        n.setId(m.getId());
        n.setParentId(m.getParentId());
        n.setPath(m.getPath());
        n.setName(m.getName());
        n.setIcon(m.getIcon());
        n.setComponent(m.getComponent());
        n.setPerms(m.getPerms());
        n.setSort(m.getSort() == null ? 0 : m.getSort());
        n.setVisible(m.getVisible() != null && m.getVisible() == 1);
        n.setChildren(new ArrayList<>());
        return n;
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
        return roles.stream().map(SysRole::getCode).sorted().toList();
    }

    private List<Long> resolveActiveRoleIds(long userId) {
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
        return roles.stream().map(SysRole::getId).toList();
    }
}
