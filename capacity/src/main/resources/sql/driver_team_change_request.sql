-- 新库初始化时可与 migrate_driver_team_change.sql 二选一；字段须与 com.sx.capacity.model.DriverTeamChangeRequest 一致
CREATE TABLE IF NOT EXISTS `driver_team_change_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `driver_id` BIGINT NOT NULL COMMENT '司机ID',
    `from_company_id` BIGINT NULL COMMENT '申请前所属运力主体(company.id)',
    `to_company_id` BIGINT NOT NULL COMMENT '目标运力主体(company.id)',
    `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
    `request_reason` VARCHAR(512) NULL COMMENT '司机申请说明',
    `requested_by` VARCHAR(64) NULL COMMENT '申请人标识',
    `requested_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `reviewed_by` VARCHAR(64) NULL COMMENT '审核人',
    `reviewed_at` DATETIME NULL COMMENT '审核时间',
    `review_reason` VARCHAR(512) NULL COMMENT '审核备注/拒绝原因',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否',
    PRIMARY KEY (`id`),
    KEY `idx_dtcr_driver` (`driver_id`),
    KEY `idx_dtcr_status` (`status`),
    KEY `idx_dtcr_requested_at` (`requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='司机换队申请';
