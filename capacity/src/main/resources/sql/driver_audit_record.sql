-- 库：capacity；司机资料审核流水表（每次审核插入一条记录）
CREATE TABLE IF NOT EXISTS `driver_audit_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `driver_id` BIGINT NOT NULL COMMENT '司机ID，关联 driver.id',
    `result_status` INT NOT NULL COMMENT '审核结果状态（与 driver.audit_status 口径一致或更细）',
    `reason` VARCHAR(2000) NULL COMMENT '驳回原因/补件要求（可扩展为 JSON）',
    `operator_type` INT NOT NULL COMMENT '操作人类型：0系统 1运营 2客服 3审核员等',
    `operator_id` BIGINT NULL COMMENT '操作人ID（可空）',
    `submission_id` BIGINT NULL COMMENT '资料提交批次/版本ID（可空，建议后续补齐）',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_driver_audit_driver` (`driver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='司机审核流水';

