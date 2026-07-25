-- P5 Wallet 生命周期投影生产回填（人工审阅、人工执行）。
-- 前提：passenger 与 wallet 位于同一 MySQL 实例，且已执行 P5 patch。

SELECT COUNT(*) AS lifecycle_identifier_control_whitespace_count
FROM `passenger`.`customer` c
WHERE c.is_deleted = 0
  AND (c.lifecycle_status REGEXP '^[[:cntrl:]]|[[:cntrl:]]$'
       OR c.current_lifecycle_operation_no REGEXP '^[[:cntrl:]]|[[:cntrl:]]$');

START TRANSACTION;

INSERT INTO `wallet`.`wallet_account_lifecycle_event_inbox`
    (`source_event_id`, `customer_id`, `lifecycle_version`, `request_hash`, `created_at`)
SELECT CONCAT('P5-WALLET-BACKFILL-', c.id, '-', c.lifecycle_version),
       c.id,
       c.lifecycle_version,
       SHA2(CONCAT(
           OCTET_LENGTH(CAST(c.id AS CHAR)), ':', c.id, ';',
           OCTET_LENGTH(CAST(c.status AS CHAR)), ':', c.status, ';',
           OCTET_LENGTH(TRIM(c.lifecycle_status)), ':', TRIM(c.lifecycle_status), ';',
           OCTET_LENGTH(CAST(c.lifecycle_version AS CHAR)), ':', c.lifecycle_version, ';',
           IF(c.current_lifecycle_operation_no IS NULL
                  OR TRIM(c.current_lifecycle_operation_no) = '', '0:;',
              CONCAT(OCTET_LENGTH(TRIM(c.current_lifecycle_operation_no)), ':',
                     TRIM(c.current_lifecycle_operation_no), ';')),
           OCTET_LENGTH(CONCAT('P5-WALLET-BACKFILL-', c.id, '-', c.lifecycle_version)), ':',
           CONCAT('P5-WALLET-BACKFILL-', c.id, '-', c.lifecycle_version), ';'
       ), 256),
       NOW()
FROM `passenger`.`customer` c
WHERE c.is_deleted = 0
ON DUPLICATE KEY UPDATE
    `request_hash` = IF(`wallet_account_lifecycle_event_inbox`.`request_hash` = VALUES(`request_hash`),
                        `wallet_account_lifecycle_event_inbox`.`request_hash`, NULL);

INSERT INTO `wallet`.`wallet_account_lifecycle_projection`
    (`customer_id`, `business_status`, `lifecycle_status`, `lifecycle_version`,
     `operation_no`, `source_event_id`, `row_version`, `updated_at`)
SELECT c.id, c.status, TRIM(c.lifecycle_status), c.lifecycle_version,
       NULLIF(TRIM(c.current_lifecycle_operation_no), ''),
       CONCAT('P5-WALLET-BACKFILL-', c.id, '-', c.lifecycle_version), 0, NOW()
FROM `passenger`.`customer` c
WHERE c.is_deleted = 0
ON DUPLICATE KEY UPDATE
    `business_status` = IF(c.lifecycle_version > wallet_account_lifecycle_projection.lifecycle_version,
                           c.status, wallet_account_lifecycle_projection.business_status),
    `lifecycle_status` = IF(c.lifecycle_version > wallet_account_lifecycle_projection.lifecycle_version,
                            TRIM(c.lifecycle_status), wallet_account_lifecycle_projection.lifecycle_status),
    `operation_no` = IF(c.lifecycle_version > wallet_account_lifecycle_projection.lifecycle_version,
                        NULLIF(TRIM(c.current_lifecycle_operation_no), ''),
                        wallet_account_lifecycle_projection.operation_no),
    `source_event_id` = IF(c.lifecycle_version > wallet_account_lifecycle_projection.lifecycle_version,
                           CONCAT('P5-WALLET-BACKFILL-', c.id, '-', c.lifecycle_version),
                           wallet_account_lifecycle_projection.source_event_id),
    `row_version` = IF(c.lifecycle_version > wallet_account_lifecycle_projection.lifecycle_version,
                       wallet_account_lifecycle_projection.row_version + 1,
                       wallet_account_lifecycle_projection.row_version),
    `updated_at` = IF(c.lifecycle_version > wallet_account_lifecycle_projection.lifecycle_version,
                      NOW(), wallet_account_lifecycle_projection.updated_at),
    `lifecycle_version` = GREATEST(wallet_account_lifecycle_projection.lifecycle_version,
                                   c.lifecycle_version);

COMMIT;

SELECT COUNT(*) AS missing_projection_count
FROM `passenger`.`customer` c
LEFT JOIN `wallet`.`wallet_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0 AND p.customer_id IS NULL;

SELECT COUNT(*) AS version_mismatch_count
FROM `passenger`.`customer` c
JOIN `wallet`.`wallet_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0 AND p.lifecycle_version < c.lifecycle_version;

SELECT COUNT(*) AS status_mismatch_count
FROM `passenger`.`customer` c
JOIN `wallet`.`wallet_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0
  AND c.lifecycle_version = p.lifecycle_version
  AND (c.status <> p.business_status
       OR TRIM(c.lifecycle_status) <> p.lifecycle_status
       OR NOT (NULLIF(TRIM(c.current_lifecycle_operation_no), '') <=> p.operation_no));

SELECT COUNT(*) AS duplicate_source_event_count
FROM (
    SELECT source_event_id
    FROM `wallet`.`wallet_account_lifecycle_projection`
    GROUP BY source_event_id
    HAVING COUNT(*) > 1
) duplicated;
