-- P5：Wallet 乘客账户生命周期投影、参与者幂等与免密解约审计。
-- 上线顺序：执行本 DDL -> 执行 wallet_account_lifecycle_p5_backfill.sql -> 核验 -> 部署代码。

USE `wallet`;

CREATE TABLE IF NOT EXISTS `wallet_account_lifecycle_event_inbox` (
    `source_event_id` VARCHAR(64) NOT NULL,
    `customer_id` BIGINT NOT NULL,
    `lifecycle_version` BIGINT NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`source_event_id`),
    KEY `idx_wallet_lifecycle_event_customer` (`customer_id`, `lifecycle_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Wallet 生命周期投影事件永久去重记录';

CREATE TABLE IF NOT EXISTS `wallet_account_lifecycle_projection` (
    `customer_id` BIGINT NOT NULL,
    `business_status` INT NOT NULL,
    `lifecycle_status` VARCHAR(24) NOT NULL,
    `lifecycle_version` BIGINT NOT NULL,
    `operation_no` VARCHAR(64) NULL,
    `source_event_id` VARCHAR(64) NOT NULL,
    `row_version` BIGINT NOT NULL DEFAULT 0,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`customer_id`),
    UNIQUE KEY `uk_wallet_lifecycle_projection_event` (`source_event_id`),
    KEY `idx_wallet_lifecycle_status_version` (`lifecycle_status`, `lifecycle_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='乘客账户生命周期 Wallet 本地投影';

CREATE TABLE IF NOT EXISTS `wallet_lifecycle_participant_inbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `operation_no` VARCHAR(64) NOT NULL,
    `step_code` VARCHAR(64) NOT NULL,
    `customer_id` BIGINT NOT NULL,
    `lifecycle_version` BIGINT NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `decision` VARCHAR(16) NOT NULL,
    `blocker_snapshot` JSON NOT NULL,
    `result_snapshot` JSON NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_lifecycle_inbox_op_step` (`operation_no`, `step_code`),
    KEY `idx_wallet_lifecycle_inbox_customer` (`customer_id`, `created_at`),
    KEY `idx_wallet_lifecycle_inbox_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Wallet 生命周期参与者命令幂等与永久结果';

CREATE TABLE IF NOT EXISTS `wallet_auto_pay_termination` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `operation_no` VARCHAR(64) NOT NULL,
    `step_code` VARCHAR(64) NOT NULL,
    `customer_id` BIGINT NOT NULL,
    `agreement_id` BIGINT NOT NULL,
    `channel` VARCHAR(32) NOT NULL,
    `agreement_no_snapshot` VARCHAR(128) NULL,
    `status` VARCHAR(24) NOT NULL COMMENT 'CONFIRMED/UNKNOWN/MANUAL_CONFIRMED',
    `channel_request_no` VARCHAR(64) NOT NULL,
    `channel_response_snapshot` JSON NOT NULL,
    `manual_actor` VARCHAR(64) NULL,
    `manual_reason` VARCHAR(512) NULL,
    `manual_evidence` VARCHAR(512) NULL,
    `resolved_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_termination_op_step_agreement`
        (`operation_no`, `step_code`, `agreement_id`),
    UNIQUE KEY `uk_wallet_termination_channel_request` (`channel_request_no`),
    KEY `idx_wallet_termination_customer_status` (`customer_id`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Wallet 生命周期免密渠道解约及人工处置审计';
