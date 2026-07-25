-- P4：Calculate 乘客账户生命周期投影、事件去重与参与者幂等结果。
-- 上线顺序：执行本 DDL -> 执行 calculate_account_lifecycle_p4_backfill.sql -> 核验覆盖率 -> 部署代码。

USE `calculate`;

-- 生命周期积分幂等键使用 operationNo:stepCode，最大 129 字符；保留余量用于版本化前缀。
ALTER TABLE `benefit_points_flow`
    MODIFY COLUMN `biz_id` VARCHAR(160) NOT NULL COMMENT '业务幂等ID；生命周期动作使用operationNo:stepCode';

CREATE TABLE IF NOT EXISTS `calculate_account_lifecycle_event_inbox` (
    `source_event_id` VARCHAR(64) NOT NULL COMMENT 'Passenger 来源事件 ID',
    `customer_id` BIGINT NOT NULL COMMENT '乘客 ID，对应 passenger.customer.id',
    `lifecycle_version` BIGINT NOT NULL COMMENT '事件携带的生命周期版本',
    `request_hash` CHAR(64) NOT NULL COMMENT '事件关键字段 SHA-256，防事件 ID 异参复用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`source_event_id`),
    KEY `idx_calculate_lifecycle_event_customer` (`customer_id`, `lifecycle_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Calculate 生命周期投影事件永久去重记录';

CREATE TABLE IF NOT EXISTS `calculate_account_lifecycle_projection` (
    `customer_id` BIGINT NOT NULL COMMENT '乘客 ID，对应 passenger.customer.id',
    `business_status` INT NOT NULL COMMENT '乘客业务状态快照，0 正常',
    `lifecycle_status` VARCHAR(24) NOT NULL COMMENT 'ACTIVE/CANCELLING/CANCELLED',
    `lifecycle_version` BIGINT NOT NULL COMMENT 'Passenger 侧单调递增版本',
    `operation_no` VARCHAR(64) NULL COMMENT '当前生命周期操作号，ACTIVE 回填可空',
    `source_event_id` VARCHAR(64) NOT NULL COMMENT '最后应用的来源事件 ID',
    `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '本地 CAS 版本',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`customer_id`),
    UNIQUE KEY `uk_calculate_lifecycle_projection_event` (`source_event_id`),
    KEY `idx_calculate_lifecycle_status_version` (`lifecycle_status`, `lifecycle_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='乘客账户生命周期 Calculate 本地投影';

CREATE TABLE IF NOT EXISTS `calculate_lifecycle_participant_inbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_no` VARCHAR(64) NOT NULL COMMENT '生命周期操作号',
    `step_code` VARCHAR(64) NOT NULL COMMENT 'Calculate 参与者步骤码',
    `customer_id` BIGINT NOT NULL COMMENT '乘客 ID',
    `lifecycle_version` BIGINT NOT NULL COMMENT '命令携带的生命周期版本',
    `request_hash` CHAR(64) NOT NULL COMMENT '命令关键字段 SHA-256',
    `status` VARCHAR(24) NOT NULL COMMENT 'PROCESSING/COMPLETED',
    `decision` VARCHAR(16) NOT NULL COMMENT 'PASS/BLOCKED/UNKNOWN',
    `blocker_snapshot` JSON NOT NULL COMMENT '结构化阻塞项快照',
    `result_snapshot` JSON NOT NULL COMMENT '动作结果或检查摘要',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_calculate_lifecycle_inbox_op_step` (`operation_no`, `step_code`),
    KEY `idx_calculate_lifecycle_inbox_customer` (`customer_id`, `created_at`),
    KEY `idx_calculate_lifecycle_inbox_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Calculate 生命周期参与者命令幂等与永久结果';
