-- P3 Order 生命周期投影生产回填（人工审阅、人工执行）
-- 前提：passenger 与 order schema 位于同一 MySQL 实例，且已执行 order_account_lifecycle_p3_patch.sql。
-- 上线顺序：DDL -> 本回填 -> 文末覆盖率检查 -> 部署 Order -> 部署 passenger-api。

-- Java String.trim() 会剔除首尾 U+0000..U+0020；生产标识只允许普通可见字符。
-- 若下列查询非 0，必须先清洗异常控制字符并停止执行后续语句。
SELECT COUNT(*) AS lifecycle_identifier_control_whitespace_count
FROM `passenger`.`customer` c
WHERE c.is_deleted = 0
  AND (c.lifecycle_status REGEXP '^[[:cntrl:]]|[[:cntrl:]]$'
       OR c.current_lifecycle_operation_no REGEXP '^[[:cntrl:]]|[[:cntrl:]]$');

START TRANSACTION;

-- 永久占用回填事件ID，避免其后被普通同步事件复用。
INSERT INTO `order`.`order_account_lifecycle_event_inbox`
    (`source_event_id`, `customer_id`, `lifecycle_version`, `request_hash`, `created_at`)
SELECT CONCAT('P3-BACKFILL-', c.id, '-', c.lifecycle_version),
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
           OCTET_LENGTH(CONCAT('P3-BACKFILL-', c.id, '-', c.lifecycle_version)), ':',
           CONCAT('P3-BACKFILL-', c.id, '-', c.lifecycle_version), ';'
       ), 256),
       NOW()
FROM `passenger`.`customer` c
WHERE c.is_deleted = 0
ON DUPLICATE KEY UPDATE
    `request_hash` = IF(`order_account_lifecycle_event_inbox`.`request_hash` = VALUES(`request_hash`),
                        `order_account_lifecycle_event_inbox`.`request_hash`, NULL);

-- lifecycle_version 必须最后赋值；前面的 IF 需要比较更新前的目标表版本。
INSERT INTO `order`.`order_account_lifecycle_projection`
    (`customer_id`, `business_status`, `lifecycle_status`, `lifecycle_version`,
     `operation_no`, `source_event_id`, `row_version`, `updated_at`)
SELECT c.id, c.status, TRIM(c.lifecycle_status), c.lifecycle_version,
       NULLIF(TRIM(c.current_lifecycle_operation_no), ''),
       CONCAT('P3-BACKFILL-', c.id, '-', c.lifecycle_version), 0, NOW()
FROM `passenger`.`customer` c
WHERE c.is_deleted = 0
ON DUPLICATE KEY UPDATE
    `business_status` = IF(c.lifecycle_version > order_account_lifecycle_projection.lifecycle_version,
                           c.status, order_account_lifecycle_projection.business_status),
    `lifecycle_status` = IF(c.lifecycle_version > order_account_lifecycle_projection.lifecycle_version,
                            TRIM(c.lifecycle_status), order_account_lifecycle_projection.lifecycle_status),
    `operation_no` = IF(c.lifecycle_version > order_account_lifecycle_projection.lifecycle_version,
                        NULLIF(TRIM(c.current_lifecycle_operation_no), ''),
                        order_account_lifecycle_projection.operation_no),
    `source_event_id` = IF(c.lifecycle_version > order_account_lifecycle_projection.lifecycle_version,
                           CONCAT('P3-BACKFILL-', c.id, '-', c.lifecycle_version),
                           order_account_lifecycle_projection.source_event_id),
    `updated_at` = IF(c.lifecycle_version > order_account_lifecycle_projection.lifecycle_version,
                      NOW(), order_account_lifecycle_projection.updated_at),
    `lifecycle_version` = GREATEST(order_account_lifecycle_projection.lifecycle_version,
                                   c.lifecycle_version);

COMMIT;

-- 覆盖率与一致性检查：所有明细查询必须返回 0 行后，才能部署 Order 硬门禁。
SELECT COUNT(*) AS passenger_customer_count
FROM `passenger`.`customer`
WHERE is_deleted = 0;

SELECT COUNT(*) AS order_projection_count
FROM `order`.`order_account_lifecycle_projection`;

SELECT c.id
FROM `passenger`.`customer` c
LEFT JOIN `order`.`order_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0 AND p.customer_id IS NULL;

SELECT c.id, c.lifecycle_version, p.lifecycle_version
FROM `passenger`.`customer` c
JOIN `order`.`order_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0 AND p.lifecycle_version < c.lifecycle_version;

SELECT c.id, c.lifecycle_status, p.lifecycle_status
FROM `passenger`.`customer` c
JOIN `order`.`order_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0 AND c.lifecycle_version = p.lifecycle_version
  AND c.lifecycle_status <> p.lifecycle_status;

SELECT c.id, c.status, p.business_status
FROM `passenger`.`customer` c
JOIN `order`.`order_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0 AND c.lifecycle_version = p.lifecycle_version
  AND c.status <> p.business_status;

SELECT c.id, c.current_lifecycle_operation_no, p.operation_no
FROM `passenger`.`customer` c
JOIN `order`.`order_account_lifecycle_projection` p ON p.customer_id = c.id
WHERE c.is_deleted = 0 AND c.lifecycle_version = p.lifecycle_version
  AND NOT (NULLIF(TRIM(c.current_lifecycle_operation_no), '') <=> p.operation_no);

SELECT p.customer_id, p.source_event_id
FROM `order`.`order_account_lifecycle_projection` p
LEFT JOIN `order`.`order_account_lifecycle_event_inbox` e
  ON e.source_event_id = p.source_event_id
WHERE e.source_event_id IS NULL;
