-- calculate 库：福利签到异常对账增量补丁
-- 用途：已有 calculate 数据库升级到“Redis Bitmap 自动收敛、MySQL 异常只留痕”口径。
-- 本补丁只新增异常问题表，不修改任何签到记录、积分流水或积分账户数据。

CREATE TABLE IF NOT EXISTS `benefit_reconciliation_issue` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `issue_key` CHAR(64) NOT NULL COMMENT '稳定问题键，SHA-256(异常类型/乘客/范围/业务主键)',
    `issue_type` VARCHAR(64) NOT NULL COMMENT '异常类型',
    `severity` VARCHAR(16) NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `sign_date` DATE NULL COMMENT '关联签到日期',
    `year_month` CHAR(6) NULL COMMENT '关联签到年月 yyyyMM',
    `reference_type` VARCHAR(32) NULL COMMENT '关联对象类型：SIGN_RECORD/POINTS_FLOW/POINTS_ACCOUNT/BITMAP',
    `reference_id` VARCHAR(64) NULL COMMENT '关联对象ID或 Bitmap offset',
    `expected_snapshot` JSON NULL COMMENT '期望值快照，不得包含手机号、token等敏感信息',
    `actual_snapshot` JSON NULL COMMENT '实际值快照，不得包含手机号、token等敏感信息',
    `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED',
    `first_detected_at` DATETIME NOT NULL COMMENT '首次发现时间',
    `last_detected_at` DATETIME NOT NULL COMMENT '最近发现时间',
    `resolved_at` DATETIME NULL COMMENT '恢复时间',
    `occurrence_count` INT NOT NULL DEFAULT 1 COMMENT '重复发现次数',
    `last_run_id` VARCHAR(64) NOT NULL COMMENT '最近一次扫描批次号，仅用于日志串联',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_benefit_reconciliation_issue_key` (`issue_key`),
    KEY `idx_benefit_issue_customer_status` (`customer_id`, `status`, `last_detected_at`),
    KEY `idx_benefit_issue_type_status` (`issue_type`, `status`, `last_detected_at`),
    KEY `idx_benefit_issue_severity_status` (`severity`, `status`, `last_detected_at`),
    KEY `idx_benefit_issue_last_run` (`last_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='福利签到与积分对账异常';
