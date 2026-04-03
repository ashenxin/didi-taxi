-- 库：capacity；字段与 com.sx.capacity.model.Company 一致
CREATE TABLE IF NOT EXISTS `company` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `city_code` VARCHAR(32) NULL COMMENT '城市编码',
    `city_name` VARCHAR(64) NULL COMMENT '城市名称',
    `province_code` VARCHAR(32) NULL COMMENT '省份编码',
    `company_no` VARCHAR(32) NOT NULL COMMENT '运力公司编号',
    `company_name` VARCHAR(128) NOT NULL COMMENT '运力公司名称',
    `team_id` BIGINT NULL COMMENT '车队ID',
    `team` VARCHAR(128) NULL COMMENT '车队名称',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运力';
