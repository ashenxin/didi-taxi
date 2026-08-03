-- =============================================================================
-- Order 订单冻结计价与结算恢复字段增量补丁（MySQL）
-- 状态：ACTIVE；所有环境执行并核验完成前保留。
--
-- 适用场景：
-- 1. trip_order 已经存在，重新执行 order_schema.sql 不会自动增加后续字段；
-- 2. 部分环境可能已经单独执行 order_settlement_payment_patch.sql；
-- 3. 本脚本通过 information_schema 判断列和索引是否存在，可以重复执行。
--
-- 注意：
-- - 历史订单不伪造 planned_distance_meters 等计价快照，新增列保持 NULL；
-- - 仅把仍在途的历史订单（0/1/2/3/4/7）标记为 blocks_new_order=1；
-- - 若同一乘客存在多个在途订单，唯一索引创建前会主动终止，需先人工处理异常数据；
-- - 本脚本包含 active_payment_no，执行后不需要再执行
--   order_settlement_payment_patch.sql。
-- =============================================================================

USE `order`;

DELIMITER $$

DROP PROCEDURE IF EXISTS `sp_order_add_column_if_missing`$$
CREATE PROCEDURE `sp_order_add_column_if_missing`(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    DECLARE v_column_count INT DEFAULT 0;

    SELECT COUNT(*)
      INTO v_column_count
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name;

    IF v_column_count = 0 THEN
        SET @order_schema_sync_ddl = CONCAT(
            'ALTER TABLE `', REPLACE(p_table_name, '`', '``'),
            '` ADD COLUMN `', REPLACE(p_column_name, '`', '``'),
            '` ', p_column_definition
        );
        PREPARE order_schema_sync_stmt FROM @order_schema_sync_ddl;
        EXECUTE order_schema_sync_stmt;
        DEALLOCATE PREPARE order_schema_sync_stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS `sp_order_add_index_if_missing`$$
CREATE PROCEDURE `sp_order_add_index_if_missing`(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_definition TEXT
)
BEGIN
    DECLARE v_index_count INT DEFAULT 0;

    SELECT COUNT(*)
      INTO v_index_count
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name;

    IF v_index_count = 0 THEN
        SET @order_schema_sync_ddl = CONCAT(
            'ALTER TABLE `', REPLACE(p_table_name, '`', '``'),
            '` ADD ', p_index_definition
        );
        PREPARE order_schema_sync_stmt FROM @order_schema_sync_ddl;
        EXECUTE order_schema_sync_stmt;
        DEALLOCATE PREPARE order_schema_sync_stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS `sp_order_assert_single_blocking_order`$$
CREATE PROCEDURE `sp_order_assert_single_blocking_order`()
BEGIN
    DECLARE v_duplicate_passengers INT DEFAULT 0;

    SELECT COUNT(*)
      INTO v_duplicate_passengers
      FROM (
          SELECT `passenger_id`
            FROM `trip_order`
           WHERE `blocks_new_order` = 1
           GROUP BY `passenger_id`
          HAVING COUNT(*) > 1
      ) duplicate_blocking_orders;

    IF v_duplicate_passengers > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '存在同一乘客多笔在途订单，已停止创建 uk_trip_order_passenger_block，请先人工处理异常数据';
    END IF;
END$$

DELIMITER ;

-- -----------------------------------------------------------------------------
-- 1. trip_order：下单冻结计价输入、mock 行程指标和并发下单阻塞位
-- -----------------------------------------------------------------------------
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'planned_distance_meters',
    'BIGINT NULL COMMENT ''下单冻结的本地mock规划距离（米）'' AFTER `fare_rule_snapshot`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'planned_duration_seconds',
    'BIGINT NULL COMMENT ''下单冻结的本地mock预计时长（秒）'' AFTER `planned_distance_meters`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'distance_source',
    'VARCHAR(32) NULL COMMENT ''距离来源，本期LOCAL_MOCK_ROUTE'' AFTER `planned_duration_seconds`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'fare_calculation_version',
    'VARCHAR(32) NULL COMMENT ''计价算法版本'' AFTER `distance_source`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'route_mock_version',
    'VARCHAR(32) NULL COMMENT ''本地mock路线版本'' AFTER `fare_calculation_version`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'mock_actual_duration_seconds',
    'BIGINT NULL COMMENT ''结算首次生成并冻结的mock实际计费时长'' AFTER `route_mock_version`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'duration_source',
    'VARCHAR(32) NULL COMMENT ''时长来源，本期LOCAL_MOCK_TRIP'' AFTER `mock_actual_duration_seconds`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'trip_metrics_version',
    'VARCHAR(32) NULL COMMENT ''mock实际指标生成版本'' AFTER `duration_source`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order', 'blocks_new_order',
    'TINYINT NULL COMMENT ''1阻止乘客创建下一单，解除后为NULL'' AFTER `trip_metrics_version`'
);

-- 历史完成/取消订单不回填阻塞位；历史完单是否存在未结清义务需由业务数据单独核对。
UPDATE `trip_order`
   SET `blocks_new_order` = 1
 WHERE `blocks_new_order` IS NULL
   AND `is_deleted` = 0
   AND `status` IN (0, 1, 2, 3, 4, 7);

CALL `sp_order_assert_single_blocking_order`();
CALL `sp_order_add_index_if_missing`(
    'trip_order', 'uk_trip_order_passenger_block',
    'UNIQUE KEY `uk_trip_order_passenger_block` (`passenger_id`, `blocks_new_order`)'
);

-- -----------------------------------------------------------------------------
-- 2. trip_order_settlement：支付确认、失败恢复、人工处置和 CAS 字段
-- -----------------------------------------------------------------------------
CALL `sp_order_add_column_if_missing`(
    'trip_order_settlement', 'active_payment_no',
    'VARCHAR(64) NULL COMMENT ''当前处理中支付尝试号，用于PAY_CONFIRMING原交易查询'' AFTER `payment_no`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order_settlement', 'failure_code',
    'VARCHAR(64) NULL COMMENT ''结算失败码'' AFTER `settlement_status`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order_settlement', 'failure_summary',
    'VARCHAR(2000) NULL COMMENT ''结算失败摘要'' AFTER `failure_code`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order_settlement', 'manual_action_required',
    'TINYINT NOT NULL DEFAULT 0 COMMENT ''是否需要运营人工处理'' AFTER `failure_summary`'
);
CALL `sp_order_add_column_if_missing`(
    'trip_order_settlement', 'version',
    'INT NOT NULL DEFAULT 0 COMMENT ''CAS版本号'' AFTER `manual_action_required`'
);

-- 只调整后续新记录的默认值；不擅自改写历史结算状态。
ALTER TABLE `trip_order_settlement`
    MODIFY COLUMN `settlement_status` VARCHAR(32) NOT NULL DEFAULT 'CALCULATING'
        COMMENT 'CALCULATING/PAY_CONFIRMING/PAYMENT_REQUIRED/PAID';

CALL `sp_order_add_index_if_missing`(
    'trip_order_settlement', 'idx_settlement_active_payment_no',
    'KEY `idx_settlement_active_payment_no` (`active_payment_no`)'
);

-- -----------------------------------------------------------------------------
-- 3. 清理临时过程
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `sp_order_assert_single_blocking_order`;
DROP PROCEDURE IF EXISTS `sp_order_add_index_if_missing`;
DROP PROCEDURE IF EXISTS `sp_order_add_column_if_missing`;

-- -----------------------------------------------------------------------------
-- 4. 执行后核验：第一条应返回 14 行，后两条应返回对应索引
-- -----------------------------------------------------------------------------
SELECT `TABLE_NAME`, `COLUMN_NAME`, `COLUMN_TYPE`, `IS_NULLABLE`, `COLUMN_DEFAULT`
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND (
       (`TABLE_NAME` = 'trip_order' AND `COLUMN_NAME` IN (
           'planned_distance_meters', 'planned_duration_seconds', 'distance_source',
           'fare_calculation_version', 'route_mock_version', 'mock_actual_duration_seconds',
           'duration_source', 'trip_metrics_version', 'blocks_new_order'
       ))
       OR
       (`TABLE_NAME` = 'trip_order_settlement' AND `COLUMN_NAME` IN (
           'active_payment_no', 'failure_code', 'failure_summary',
           'manual_action_required', 'version'
       ))
   )
 ORDER BY `TABLE_NAME`, `ORDINAL_POSITION`;

SELECT `INDEX_NAME`, GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX`) AS `INDEX_COLUMNS`
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND `TABLE_NAME` = 'trip_order'
   AND `INDEX_NAME` = 'uk_trip_order_passenger_block'
 GROUP BY `INDEX_NAME`;

SELECT `INDEX_NAME`, GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX`) AS `INDEX_COLUMNS`
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND `TABLE_NAME` = 'trip_order_settlement'
   AND `INDEX_NAME` = 'idx_settlement_active_payment_no'
 GROUP BY `INDEX_NAME`;
