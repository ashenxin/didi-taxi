-- 计费服务 calculate 库 - 计价规则表
CREATE DATABASE IF NOT EXISTS `calculate` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `calculate`;

CREATE TABLE IF NOT EXISTS `fare_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',

    `province_code` VARCHAR(32) NOT NULL COMMENT '省份编码',
    `city_code` VARCHAR(32) NOT NULL COMMENT '城市编码',
    `product_code` VARCHAR(64) NOT NULL COMMENT '产品线/车型档编码，与订单、运力侧约定一致',

    `rule_name` VARCHAR(128) NULL COMMENT '规则名称，便于运营识别',

    `effective_from` DATETIME NOT NULL COMMENT '生效开始时间',
    `effective_to` DATETIME NULL COMMENT '生效结束时间，NULL 表示当前仍有效',

    `base_fare` DECIMAL(10, 2) NOT NULL COMMENT '起步价',
    `included_distance_km` DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '起步含里程（公里）',
    `included_duration_min` INT NOT NULL DEFAULT 0 COMMENT '起步含时长（分钟）',
    `per_km_price` DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '超出后每公里单价',
    `per_minute_price` DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '超出后每分钟单价',
    `minimum_fare` DECIMAL(10, 2) NULL COMMENT '最低消费，NULL 表示不启用',
    `maximum_fare` DECIMAL(10, 2) NULL COMMENT '封顶价，NULL 表示不启用',

    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0 未删除，非 0 已删除',

    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    KEY `idx_fare_rule_province_city_product` (`province_code`, `city_code`, `product_code`),
    KEY `idx_fare_rule_city_product` (`city_code`, `product_code`),
    KEY `idx_fare_rule_effective` (`effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计价规则';
