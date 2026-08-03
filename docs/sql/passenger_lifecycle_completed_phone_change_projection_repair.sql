-- 已完成换号后，下游生命周期投影仍残留 operation_no 的专项修复。
--
-- 适用范围：Passenger、Order、Calculate、Wallet 位于同一 MySQL 8 实例。
-- 当前已审计的预期候选数为 3；任何候选数、实际更新数或最终校验不符合预期，
-- CHECK 约束都会使脚本报错，事务不得提交。
--
-- 执行前：
--   1. 先部署并重启已修复的 Passenger、Wallet 服务，避免新消息再次写入旧值；
--   2. 使用不带 --force 的 mysql 客户端执行；
--   3. 确认失败后执行 ROLLBACK，并保留报错及候选明细。

DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_projection_repair`;
DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_candidate_guard`;
DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_update_guard`;
DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_verify_guard`;

START TRANSACTION;

CREATE TEMPORARY TABLE `tmp_completed_phone_change_projection_repair` (
    `customer_id` BIGINT NOT NULL,
    `lifecycle_version` BIGINT NOT NULL,
    `business_status` INT NOT NULL,
    `lifecycle_status` VARCHAR(24) NOT NULL,
    `old_operation_no` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`customer_id`)
) ENGINE=InnoDB;

-- 仅选择满足以下全部条件的记录：
-- Passenger 权威状态为 ACTIVE 且无当前操作号；三个下游投影状态/版本完全一致；
-- 三个投影残留同一操作号；该操作确为同一乘客已完成的 PHONE_CHANGE，且完成版本一致。
INSERT INTO `tmp_completed_phone_change_projection_repair`
    (`customer_id`, `lifecycle_version`, `business_status`, `lifecycle_status`, `old_operation_no`)
SELECT c.id,
       c.lifecycle_version,
       c.status,
       TRIM(c.lifecycle_status),
       o.operation_no
FROM `passenger`.`customer` c
JOIN `passenger`.`account_lifecycle_operation` op
  ON op.customer_id = c.id
 AND op.operation_type = 'PHONE_CHANGE'
 AND op.status = 'COMPLETED'
 AND op.applied_lifecycle_version = c.lifecycle_version
JOIN `order`.`order_account_lifecycle_projection` o
  ON o.customer_id = c.id
 AND o.lifecycle_version = c.lifecycle_version
 AND o.business_status = c.status
 AND o.lifecycle_status = TRIM(c.lifecycle_status)
 AND o.operation_no = op.operation_no
JOIN `calculate`.`calculate_account_lifecycle_projection` ca
  ON ca.customer_id = c.id
 AND ca.lifecycle_version = c.lifecycle_version
 AND ca.business_status = c.status
 AND ca.lifecycle_status = TRIM(c.lifecycle_status)
 AND ca.operation_no = op.operation_no
JOIN `wallet`.`wallet_account_lifecycle_projection` w
  ON w.customer_id = c.id
 AND w.lifecycle_version = c.lifecycle_version
 AND w.business_status = c.status
 AND w.lifecycle_status = TRIM(c.lifecycle_status)
 AND w.operation_no = op.operation_no
WHERE c.is_deleted = 0
  AND c.status = 0
  AND TRIM(c.lifecycle_status) = 'ACTIVE'
  AND NULLIF(TRIM(c.current_lifecycle_operation_no), '') IS NULL;

-- 人工留档：预期应显示 customer_id 10001、10011、10016，共 3 行。
SELECT *
FROM `tmp_completed_phone_change_projection_repair`
ORDER BY customer_id;

-- MySQL 8.0.16+ 会强制执行 CHECK；不是恰好 3 条时在这里中止。
CREATE TEMPORARY TABLE `tmp_completed_phone_change_candidate_guard` (
    `candidate_count` INT NOT NULL,
    CONSTRAINT `chk_completed_phone_change_candidate_count`
        CHECK (`candidate_count` = 3)
) ENGINE=InnoDB;

INSERT INTO `tmp_completed_phone_change_candidate_guard` (`candidate_count`)
SELECT COUNT(*)
FROM `tmp_completed_phone_change_projection_repair`;

-- 为三次修复分别占用新的事件 ID。若事件 ID 曾被异参占用，故意写 NULL 触发失败。
INSERT INTO `order`.`order_account_lifecycle_event_inbox`
    (`source_event_id`, `customer_id`, `lifecycle_version`, `request_hash`, `created_at`)
SELECT CONCAT('OPNO-REPAIR-O-', r.customer_id, '-', r.lifecycle_version),
       r.customer_id,
       r.lifecycle_version,
       SHA2(CONCAT(
           OCTET_LENGTH(CAST(r.customer_id AS CHAR)), ':', r.customer_id, ';',
           OCTET_LENGTH(CAST(r.business_status AS CHAR)), ':', r.business_status, ';',
           OCTET_LENGTH(r.lifecycle_status), ':', r.lifecycle_status, ';',
           OCTET_LENGTH(CAST(r.lifecycle_version AS CHAR)), ':', r.lifecycle_version, ';',
           '0:;',
           OCTET_LENGTH(CONCAT('OPNO-REPAIR-O-', r.customer_id, '-', r.lifecycle_version)), ':',
           CONCAT('OPNO-REPAIR-O-', r.customer_id, '-', r.lifecycle_version), ';'
       ), 256),
       NOW()
FROM `tmp_completed_phone_change_projection_repair` r
ON DUPLICATE KEY UPDATE
    `request_hash` = IF(
        `order_account_lifecycle_event_inbox`.`customer_id` = VALUES(`customer_id`)
        AND `order_account_lifecycle_event_inbox`.`lifecycle_version` = VALUES(`lifecycle_version`)
        AND `order_account_lifecycle_event_inbox`.`request_hash` = VALUES(`request_hash`),
        `order_account_lifecycle_event_inbox`.`request_hash`, NULL);

INSERT INTO `calculate`.`calculate_account_lifecycle_event_inbox`
    (`source_event_id`, `customer_id`, `lifecycle_version`, `request_hash`, `created_at`)
SELECT CONCAT('OPNO-REPAIR-C-', r.customer_id, '-', r.lifecycle_version),
       r.customer_id,
       r.lifecycle_version,
       SHA2(CONCAT(
           OCTET_LENGTH(CAST(r.customer_id AS CHAR)), ':', r.customer_id, ';',
           OCTET_LENGTH(CAST(r.business_status AS CHAR)), ':', r.business_status, ';',
           OCTET_LENGTH(r.lifecycle_status), ':', r.lifecycle_status, ';',
           OCTET_LENGTH(CAST(r.lifecycle_version AS CHAR)), ':', r.lifecycle_version, ';',
           '0:;',
           OCTET_LENGTH(CONCAT('OPNO-REPAIR-C-', r.customer_id, '-', r.lifecycle_version)), ':',
           CONCAT('OPNO-REPAIR-C-', r.customer_id, '-', r.lifecycle_version), ';'
       ), 256),
       NOW()
FROM `tmp_completed_phone_change_projection_repair` r
ON DUPLICATE KEY UPDATE
    `request_hash` = IF(
        `calculate_account_lifecycle_event_inbox`.`customer_id` = VALUES(`customer_id`)
        AND `calculate_account_lifecycle_event_inbox`.`lifecycle_version` = VALUES(`lifecycle_version`)
        AND `calculate_account_lifecycle_event_inbox`.`request_hash` = VALUES(`request_hash`),
        `calculate_account_lifecycle_event_inbox`.`request_hash`, NULL);

INSERT INTO `wallet`.`wallet_account_lifecycle_event_inbox`
    (`source_event_id`, `customer_id`, `lifecycle_version`, `request_hash`, `created_at`)
SELECT CONCAT('OPNO-REPAIR-W-', r.customer_id, '-', r.lifecycle_version),
       r.customer_id,
       r.lifecycle_version,
       SHA2(CONCAT(
           OCTET_LENGTH(CAST(r.customer_id AS CHAR)), ':', r.customer_id, ';',
           OCTET_LENGTH(CAST(r.business_status AS CHAR)), ':', r.business_status, ';',
           OCTET_LENGTH(r.lifecycle_status), ':', r.lifecycle_status, ';',
           OCTET_LENGTH(CAST(r.lifecycle_version AS CHAR)), ':', r.lifecycle_version, ';',
           '0:;',
           OCTET_LENGTH(CONCAT('OPNO-REPAIR-W-', r.customer_id, '-', r.lifecycle_version)), ':',
           CONCAT('OPNO-REPAIR-W-', r.customer_id, '-', r.lifecycle_version), ';'
       ), 256),
       NOW()
FROM `tmp_completed_phone_change_projection_repair` r
ON DUPLICATE KEY UPDATE
    `request_hash` = IF(
        `wallet_account_lifecycle_event_inbox`.`customer_id` = VALUES(`customer_id`)
        AND `wallet_account_lifecycle_event_inbox`.`lifecycle_version` = VALUES(`lifecycle_version`)
        AND `wallet_account_lifecycle_event_inbox`.`request_hash` = VALUES(`request_hash`),
        `wallet_account_lifecycle_event_inbox`.`request_hash`, NULL);

UPDATE `order`.`order_account_lifecycle_projection` p
JOIN `tmp_completed_phone_change_projection_repair` r
  ON r.customer_id = p.customer_id
 AND r.lifecycle_version = p.lifecycle_version
 AND r.old_operation_no = p.operation_no
SET p.operation_no = NULL,
    p.source_event_id = CONCAT('OPNO-REPAIR-O-', r.customer_id, '-', r.lifecycle_version),
    p.row_version = p.row_version + 1,
    p.updated_at = NOW();
SET @order_updated := ROW_COUNT();

UPDATE `calculate`.`calculate_account_lifecycle_projection` p
JOIN `tmp_completed_phone_change_projection_repair` r
  ON r.customer_id = p.customer_id
 AND r.lifecycle_version = p.lifecycle_version
 AND r.old_operation_no = p.operation_no
SET p.operation_no = NULL,
    p.source_event_id = CONCAT('OPNO-REPAIR-C-', r.customer_id, '-', r.lifecycle_version),
    p.row_version = p.row_version + 1,
    p.updated_at = NOW();
SET @calculate_updated := ROW_COUNT();

UPDATE `wallet`.`wallet_account_lifecycle_projection` p
JOIN `tmp_completed_phone_change_projection_repair` r
  ON r.customer_id = p.customer_id
 AND r.lifecycle_version = p.lifecycle_version
 AND r.old_operation_no = p.operation_no
SET p.operation_no = NULL,
    p.source_event_id = CONCAT('OPNO-REPAIR-W-', r.customer_id, '-', r.lifecycle_version),
    p.row_version = p.row_version + 1,
    p.updated_at = NOW();
SET @wallet_updated := ROW_COUNT();

CREATE TEMPORARY TABLE `tmp_completed_phone_change_update_guard` (
    `domain_name` VARCHAR(16) NOT NULL,
    `updated_count` INT NOT NULL,
    CONSTRAINT `chk_completed_phone_change_updated_count`
        CHECK (`updated_count` = 3)
) ENGINE=InnoDB;

INSERT INTO `tmp_completed_phone_change_update_guard` (`domain_name`, `updated_count`)
VALUES ('order', @order_updated),
       ('calculate', @calculate_updated),
       ('wallet', @wallet_updated);

-- 全量一致性门禁：三个领域的缺失、落后、同版本状态/操作号不一致和孤儿事件必须均为 0。
CREATE TEMPORARY TABLE `tmp_completed_phone_change_verify_guard` (
    `metric_name` VARCHAR(64) NOT NULL,
    `issue_count` INT NOT NULL,
    CONSTRAINT `chk_completed_phone_change_issue_count`
        CHECK (`issue_count` = 0)
) ENGINE=InnoDB;

INSERT INTO `tmp_completed_phone_change_verify_guard` (`metric_name`, `issue_count`)
SELECT 'order_projection_mismatch', COUNT(*)
FROM `passenger`.`customer` c
LEFT JOIN `order`.`order_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0
  AND (p.customer_id IS NULL
       OR p.lifecycle_version <> c.lifecycle_version
       OR p.business_status <> c.status
       OR p.lifecycle_status <> TRIM(c.lifecycle_status)
       OR NOT (p.operation_no <=> NULLIF(TRIM(c.current_lifecycle_operation_no), '')))
UNION ALL
SELECT 'calculate_projection_mismatch', COUNT(*)
FROM `passenger`.`customer` c
LEFT JOIN `calculate`.`calculate_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0
  AND (p.customer_id IS NULL
       OR p.lifecycle_version <> c.lifecycle_version
       OR p.business_status <> c.status
       OR p.lifecycle_status <> TRIM(c.lifecycle_status)
       OR NOT (p.operation_no <=> NULLIF(TRIM(c.current_lifecycle_operation_no), '')))
UNION ALL
SELECT 'wallet_projection_mismatch', COUNT(*)
FROM `passenger`.`customer` c
LEFT JOIN `wallet`.`wallet_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0
  AND (p.customer_id IS NULL
       OR p.lifecycle_version <> c.lifecycle_version
       OR p.business_status <> c.status
       OR p.lifecycle_status <> TRIM(c.lifecycle_status)
       OR NOT (p.operation_no <=> NULLIF(TRIM(c.current_lifecycle_operation_no), '')))
UNION ALL
SELECT 'order_orphan_source_event', COUNT(*)
FROM `order`.`order_account_lifecycle_projection` p
LEFT JOIN `order`.`order_account_lifecycle_event_inbox` e ON e.source_event_id = p.source_event_id
WHERE e.source_event_id IS NULL
UNION ALL
SELECT 'calculate_orphan_source_event', COUNT(*)
FROM `calculate`.`calculate_account_lifecycle_projection` p
LEFT JOIN `calculate`.`calculate_account_lifecycle_event_inbox` e ON e.source_event_id = p.source_event_id
WHERE e.source_event_id IS NULL
UNION ALL
SELECT 'wallet_orphan_source_event', COUNT(*)
FROM `wallet`.`wallet_account_lifecycle_projection` p
LEFT JOIN `wallet`.`wallet_account_lifecycle_event_inbox` e ON e.source_event_id = p.source_event_id
WHERE e.source_event_id IS NULL;

SELECT * FROM `tmp_completed_phone_change_update_guard` ORDER BY domain_name;
SELECT * FROM `tmp_completed_phone_change_verify_guard` ORDER BY metric_name;

COMMIT;

DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_verify_guard`;
DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_update_guard`;
DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_candidate_guard`;
DROP TEMPORARY TABLE IF EXISTS `tmp_completed_phone_change_projection_repair`;
