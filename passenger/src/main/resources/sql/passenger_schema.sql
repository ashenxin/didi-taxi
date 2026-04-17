-- =============================================================================
-- passenger 库：建表（乘客 customer / OAuth 绑定 / 后台 sys_*）
-- 种子数据见 passenger_seed.sql
-- =============================================================================

-- 乘客服务 passenger 库（表结构后续按需补充）
CREATE DATABASE IF NOT EXISTS `passenger` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `passenger`;

CREATE TABLE IF NOT EXISTS `customer` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',

    `phone` VARCHAR(32) NOT NULL COMMENT '手机号，登录主键；未删除记录在业务上唯一',
    `password_hash` VARCHAR(128) NULL COMMENT '密码摘要；若仅短信登录可无',
    `nickname` VARCHAR(64) NULL COMMENT '昵称',
    `avatar_url` VARCHAR(512) NULL COMMENT '头像地址',

    `status` INT NOT NULL DEFAULT 0 COMMENT '账号状态：0 正常，1 冻结等（枚举可后续统一）',

    `real_name` VARCHAR(64) NULL COMMENT '真实姓名，按需实名',
    `id_card_no` VARCHAR(32) NULL COMMENT '证件号，敏感字段，存储需加密/脱敏策略',

    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0 未删除',

    `phone_active` VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN `is_deleted` = 0 THEN `phone` ELSE NULL END) STORED
        COMMENT '未删除时等于 phone，已删除为 NULL；唯一索引保证「未删」手机号不重复',

    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_customer_phone_active` (`phone_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客用户';


CREATE TABLE IF NOT EXISTS `customer_oauth_binding` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT 'customer.id',
    `provider` VARCHAR(32) NOT NULL COMMENT '第三方标识，如 wechat_mp、wechat_app、alipay',
    `provider_user_id` VARCHAR(128) NOT NULL COMMENT '第三方用户唯一标识，如 openid、unionid 视 provider 而定',
    `raw_profile_json` JSON NULL COMMENT '可选：授权后拉取的用户信息快照',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_oauth_provider_user` (`provider`, `provider_user_id`),
    KEY `idx_oauth_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客第三方账号绑定';

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
