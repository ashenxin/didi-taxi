-- =============================================================================
-- passenger 库：全量种子（RBAC + 演示乘客）
-- =============================================================================

-- =============================================================================
-- 后台管理系统 RBAC：菜单 + 按钮权限（perms）+ 角色绑定 — 与当前 admin-api 对齐
-- 库：passenger（与 customer 同库）
-- 说明：
--   1) 仅含 sys_menu / sys_role / sys_role_menu；无独立 sys_permission 表（perms 在菜单行）。
--   2) 菜单「按钮」使用 parent_id 指向页面菜单、visible=0、component=NULL，供前端 v-permission 等使用。
--   3) 角色语义与 passenger.AdminSysMenuService 一致：SUPER/省管全量菜单；市管排除 path 以 /system 开头。
--   4) 执行前请备份；若需保留已有账号，请勿清空 sys_user / sys_user_role。
--   5) 勿删 sys_role 主键行；若清空 sys_role 会导致 sys_user_role.role_id 失效，需手工改绑。
--   6) 对应后台 BFF：/admin/api/v1/orders、capacity、pricing、capacity/team-change-requests、system/admin-users。
-- =============================================================================

USE `passenger`;

SET NAMES utf8mb4;

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- 1. 清空旧权限数据（菜单与绑定）
-- ---------------------------------------------------------------------------
DELETE FROM `sys_role_menu`;
DELETE FROM `sys_menu`;

-- ---------------------------------------------------------------------------
-- 2. 角色（三角色；若已存在则按 code 更新名称/状态）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role` (`code`, `name`, `remark`, `sort`, `status`, `is_deleted`)
VALUES
    ('SUPER', '超级管理员', '全菜单与全数据域', 1, 1, 0),
    ('PROVINCE_ADMIN', '省份管理员', '单省数据域；菜单与超管一致（接口按省裁剪）', 2, 1, 0),
    ('CITY_OPERATOR', '城市操作员', '单市数据域；不含系统管理（/system）', 3, 1, 0)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `remark` = VALUES(`remark`),
    `sort` = VALUES(`sort`),
    `status` = VALUES(`status`),
    `is_deleted` = 0;

-- ---------------------------------------------------------------------------
-- 3. 菜单树（一级分组 + 页面 + 按钮权限）
-- ---------------------------------------------------------------------------

-- 3.1 订单管理
INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (NULL, '/order-mgmt', '订单管理', NULL, NULL, NULL, 10, 1, 1, 0);
SET @m_order_root := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (@m_order_root, '/orders', '订单列表', NULL, 'order/OrderListView.vue', 'order:list', 1, 1, 1, 0);
SET @m_orders := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_orders, '/orders', '查看详情', NULL, NULL, 'order:detail', 11, 0, 1, 0);

-- 3.2 运力审核
INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (NULL, '/capacity', '运力审核', NULL, NULL, NULL, 20, 1, 1, 0);
SET @m_cap := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (@m_cap, '/capacity/companies', '运力公司', NULL, 'capacity/CompanyListView.vue', 'capacity:company:list', 1, 1, 1, 0);
SET @m_companies := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_companies, '/capacity/companies', '新增公司', NULL, NULL, 'capacity:company:create', 11, 0, 1, 0),
    (@m_companies, '/capacity/companies', '编辑公司', NULL, NULL, 'capacity:company:edit', 12, 0, 1, 0),
    (@m_companies, '/capacity/companies', '删除公司', NULL, NULL, 'capacity:company:delete', 13, 0, 1, 0);

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (@m_cap, '/capacity/drivers', '司机', NULL, 'capacity/DriverListView.vue', 'capacity:driver:list', 2, 1, 1, 0);
SET @m_drivers := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_drivers, '/capacity/drivers', '司机详情', NULL, NULL, 'capacity:driver:detail', 11, 0, 1, 0);

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (@m_cap, '/capacity/team-change-requests', '换队审核', NULL, 'capacity/TeamChangeListView.vue', 'capacity:team-change:list', 3, 1, 1, 0);
SET @m_team := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_team, '/capacity/team-change-requests', '换队详情', NULL, NULL, 'capacity:team-change:detail', 11, 0, 1, 0),
    (@m_team, '/capacity/team-change-requests', '审核通过', NULL, NULL, 'capacity:team-change:approve', 12, 0, 1, 0),
    (@m_team, '/capacity/team-change-requests', '审核拒绝', NULL, NULL, 'capacity:team-change:reject', 13, 0, 1, 0);

-- 3.3 计价管理
INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (NULL, '/pricing', '计价管理', NULL, NULL, NULL, 30, 1, 1, 0);
SET @m_pri := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (@m_pri, '/pricing/fare-rules', '计价规则', NULL, 'pricing/FareRuleListView.vue', 'pricing:fare-rule:list', 1, 1, 1, 0);
SET @m_fare := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_fare, '/pricing/fare-rules', '新增规则', NULL, NULL, 'pricing:fare-rule:create', 11, 0, 1, 0),
    (@m_fare, '/pricing/fare-rules', '编辑规则', NULL, NULL, 'pricing:fare-rule:edit', 12, 0, 1, 0),
    (@m_fare, '/pricing/fare-rules', '删除规则', NULL, NULL, 'pricing:fare-rule:delete', 13, 0, 1, 0);

-- 3.4 系统管理（市管角色在 Java 侧按 path 前缀 /system 排除）
INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (NULL, '/system', '系统管理', NULL, NULL, NULL, 40, 1, 1, 0);
SET @m_sys := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES (@m_sys, '/system/admin-users', '管理员', NULL, 'system/AdminUserListView.vue', 'system:admin-user:list', 1, 1, 1, 0);
SET @m_admin := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_admin, '/system/admin-users', '新增管理员', NULL, NULL, 'system:admin-user:create', 11, 0, 1, 0),
    (@m_admin, '/system/admin-users', '编辑管理员', NULL, NULL, 'system:admin-user:edit', 12, 0, 1, 0),
    (@m_admin, '/system/admin-users', '删除管理员', NULL, NULL, 'system:admin-user:delete', 13, 0, 1, 0);

-- ---------------------------------------------------------------------------
-- 4. 角色-菜单绑定
--    SUPER / PROVINCE_ADMIN：挂全部有效菜单（含按钮行）
--    CITY_OPERATOR：排除 path 以 /system 开头的菜单（与 AdminSysMenuService 一致）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id`
FROM `sys_role` r
         CROSS JOIN `sys_menu` m
WHERE r.`code` IN ('SUPER', 'PROVINCE_ADMIN')
  AND r.`is_deleted` = 0
  AND m.`is_deleted` = 0
  AND m.`status` = 1;

INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id`
FROM `sys_role` r
         CROSS JOIN `sys_menu` m
WHERE r.`code` = 'CITY_OPERATOR'
  AND r.`is_deleted` = 0
  AND m.`is_deleted` = 0
  AND m.`status` = 1
  AND (m.`path` IS NULL OR m.`path` NOT LIKE '/system%');

COMMIT;

-- ---------------------------------------------------------------------------
-- 可选校验
-- SELECT COUNT(*) FROM sys_menu WHERE is_deleted = 0;
-- SELECT r.code, COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id GROUP BY r.code;
-- =============================================================================


DELETE FROM `customer` WHERE `id` IN (10001, 10002, 10003);

INSERT INTO `customer` (
    `id`, `phone`, `password_hash`, `nickname`, `avatar_url`,
    `status`, `real_name`, `id_card_no`, `is_deleted`
) VALUES
(10001, '13800138000', NULL, '乘客A', NULL, 0, NULL, NULL, 0),
(10002, '13900139000', NULL, '乘客B', NULL, 0, NULL, NULL, 0),
(10003, '13700137000', NULL, '乘客C', NULL, 0, NULL, NULL, 0);

USE `passenger`;

UPDATE `customer`
SET `password_hash` = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
WHERE `phone` = '13800138000' AND `is_deleted` = 0;
