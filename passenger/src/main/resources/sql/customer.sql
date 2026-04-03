-- 乘客主表 customer：手机号仅在未逻辑删除（is_deleted=0）时唯一，通过生成列 phone_active + 唯一索引实现
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
