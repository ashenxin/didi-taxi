-- =============================================================================
-- 后台管理系统 RBAC：库 passenger，表前缀 sys_
-- 与《后台管理系统_权限清单与鉴权设计.md》《后台管理系统_权限与接口文档.md》对齐
-- MySQL 8.0+（使用生成列实现软删后用户名可复用）
-- =============================================================================

USE `passenger`;

-- -----------------------------------------------------------------------------
-- 角色
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`         VARCHAR(64)  NOT NULL COMMENT 'SUPER / PROVINCE_ADMIN / CITY_OPERATOR',
    `name`         VARCHAR(128) NOT NULL COMMENT '展示名称',
    `remark`       VARCHAR(255)          DEFAULT NULL,
    `sort`         INT          NOT NULL DEFAULT 0,
    `status`       INT          NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   INT          NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_code` (`code`),
    KEY `idx_sys_role_status` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台角色';

-- -----------------------------------------------------------------------------
-- 后台用户（与 passenger.customer 无关）
-- token_version：改角色/权限/省市区/禁用/改密后递增，用于 JWT 立即失效
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(64)   NOT NULL COMMENT '登录名',
    `password_hash`   VARCHAR(255)  NOT NULL COMMENT 'BCrypt 等',
    `display_name`    VARCHAR(128)           DEFAULT NULL,
    `province_code`   VARCHAR(32)            DEFAULT NULL COMMENT '省编码；超管NULL；省管必填；市员与city配套',
    `city_code`       VARCHAR(32)            DEFAULT NULL COMMENT '市编码；超管、省管NULL；市员必填',
    `token_version`   BIGINT        NOT NULL DEFAULT 0 COMMENT 'JWT 版本，敏感变更后 +1',
    `status`          INT           NOT NULL DEFAULT 1 COMMENT '1正常 0停用等',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT                 DEFAULT NULL COMMENT '创建人 sys_user.id',
    `updated_by`      BIGINT                 DEFAULT NULL,
    `is_deleted`      INT           NOT NULL DEFAULT 0,
    `username_active` VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN `is_deleted` = 0 THEN `username` ELSE NULL END) STORED
        COMMENT '未删时等于 username，已删为 NULL；唯一保证在用登录名不重复',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username_active` (`username_active`),
    KEY `idx_sys_user_province_city` (`province_code`, `city_code`, `status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台用户';

-- -----------------------------------------------------------------------------
-- 用户-角色
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `user_id`    BIGINT   NOT NULL,
    `role_id`    BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_sys_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-角色';

-- -----------------------------------------------------------------------------
-- 菜单（权限清单 + 前端路由）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_menu` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `parent_id`   BIGINT                 DEFAULT NULL,
    `path`        VARCHAR(255)  NOT NULL COMMENT '路由 path',
    `name`        VARCHAR(128)  NOT NULL COMMENT '菜单标题',
    `icon`        VARCHAR(64)            DEFAULT NULL,
    `component`   VARCHAR(255)           DEFAULT NULL COMMENT '前端组件路径',
    `perms`       VARCHAR(256)           DEFAULT NULL COMMENT '权限标识，如 order:list',
    `sort`        INT           NOT NULL DEFAULT 0,
    `visible`     INT           NOT NULL DEFAULT 1 COMMENT '1显示 0隐藏',
    `status`      INT           NOT NULL DEFAULT 1,
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_sys_menu_parent` (`parent_id`, `sort`),
    KEY `idx_sys_menu_status` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台菜单';

-- -----------------------------------------------------------------------------
-- 角色-菜单
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
    `role_id`    BIGINT   NOT NULL,
    `menu_id`    BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`role_id`, `menu_id`),
    KEY `idx_sys_role_menu_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-菜单';

-- =============================================================================
-- 初始化角色（可按环境重复执行前先 DELETE 或用手工维护）
-- =============================================================================
INSERT INTO `sys_role` (`code`, `name`, `remark`, `sort`, `status`, `is_deleted`)
VALUES
    ('SUPER', '超级管理员', '全部菜单与数据', 1, 1, 0),
    ('PROVINCE_ADMIN', '省份管理员', '单省 + 本市操作员管理', 2, 1, 0),
    ('CITY_OPERATOR', '城市操作员', '单市日常维护', 3, 1, 0)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- =============================================================================
-- 超级管理员：关联「当前全部有效菜单」（菜单数据导入后执行；新增菜单后需再执行一次）
-- 将 :super_role_id 替换为 SUPER 对应 id（通常首次插入后为 1）
-- =============================================================================
-- DELETE FROM `sys_role_menu` WHERE `role_id` = 1;
-- INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
-- SELECT 1, `id` FROM `sys_menu` WHERE `is_deleted` = 0 AND `status` = 1;

-- =============================================================================
-- 首个超级管理员：须手动执行；密码请用 BCrypt 生成后替换下方占位
-- 示例（勿提交真实密码）：用户名 admin，需自行 INSERT sys_user + sys_user_role
-- =============================================================================
-- 开发联调示例（明文密码 admin123，仅限本地；生产请删除并自建账号）：
-- INSERT INTO `sys_user` (`username`, `password_hash`, `display_name`, `province_code`, `city_code`, `token_version`, `status`, `is_deleted`)
-- VALUES ('admin', '$2a$10$zinv.ZcwB3B70TasTDRQsOb.9wh4EkPlak0hSQfsLghl6qmg8xtXy', '超管', NULL, NULL, 0, 1, 0);
-- INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES (LAST_INSERT_ID(), (SELECT id FROM sys_role WHERE code = 'SUPER' LIMIT 1));

-- =============================================================================
-- 初始化菜单（与 didi-Vue 中 src/views 下路径一致，component 为相对 views 的文件路径）
-- 执行后可执行：INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'SUPER' AND m.is_deleted = 0;
-- =============================================================================
INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (NULL, '/orders', '订单管理', NULL, 'order/OrderListView.vue', 'order:list', 10, 1, 1, 0);
SET @m_orders := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (NULL, '/capacity', '运力审核', NULL, NULL, NULL, 20, 1, 1, 0);
SET @m_cap := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_cap, '/capacity/companies', '运力公司', NULL, 'capacity/CompanyListView.vue', 'capacity:company:list', 1, 1, 1, 0),
    (@m_cap, '/capacity/drivers', '司机', NULL, 'capacity/DriverListView.vue', 'capacity:driver:list', 2, 1, 1, 0),
    (@m_cap, '/capacity/team-change-requests', '换队审核', NULL, 'capacity/TeamChangeListView.vue', 'capacity:team-change:list', 3, 1, 1, 0);

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (NULL, '/pricing', '计价管理', NULL, NULL, NULL, 30, 1, 1, 0);
SET @m_pri := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_pri, '/pricing/fare-rules', '计价规则', NULL, 'pricing/FareRuleListView.vue', 'pricing:fare-rule:list', 1, 1, 1, 0);

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (NULL, '/system', '系统管理', NULL, NULL, NULL, 40, 1, 1, 0);
SET @m_sys := LAST_INSERT_ID();

INSERT INTO `sys_menu` (`parent_id`, `path`, `name`, `icon`, `component`, `perms`, `sort`, `visible`, `status`, `is_deleted`)
VALUES
    (@m_sys, '/system/admin-users', '管理员', NULL, 'system/AdminUserListView.vue', 'system:admin-user:list', 1, 1, 1, 0);

/*
 * 省管理员：与 SUPER 全量菜单一致（实际可见树由 Java 侧对 PROVINCE_ADMIN 按「全菜单」解析；
 * 此处写入 sys_role_menu 便于报表/审计与其它工具读库一致，可重复执行）。
 */
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r CROSS JOIN `sys_menu` m
WHERE r.`code` = 'PROVINCE_ADMIN' AND r.`is_deleted` = 0 AND m.`is_deleted` = 0 AND m.`status` = 1;

/*
 * 市管理员：全业务菜单但不含「系统管理」及其子路径（不可管理同级后台用户；
 * 实际可见树由 Java 对 CITY_OPERATOR 排除 path 以 /system 开头的菜单）。
 */
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r CROSS JOIN `sys_menu` m
WHERE r.`code` = 'CITY_OPERATOR' AND r.`is_deleted` = 0 AND m.`is_deleted` = 0 AND m.`status` = 1
  AND (m.`path` IS NULL OR m.`path` NOT LIKE '/system%');

/* 为 SUPER 挂上全部有效菜单（可重复执行；主键冲突则 ignore） */
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r CROSS JOIN `sys_menu` m
WHERE r.`code` = 'SUPER' AND r.`is_deleted` = 0 AND m.`is_deleted` = 0 AND m.`status` = 1;
