-- =============================================================================
-- passenger 库：移除 passenger_ai_message 冗余 customer_id 的增量 DDL
--
-- 目的：
-- 消息归属由 conversation_id 关联 passenger_ai_conversation 取得，查询消息前先校验会话的
-- customer_id；消息表不再重复保存乘客 ID。本脚本只删除这一列，不改动会话表。
--
-- 注意：
-- - 修改 CREATE TABLE IF NOT EXISTS 脚本不会升级已经存在的表，必须单独执行本脚本；
--   passenger_schema.sql 与 passenger_ai_conversation_patch.sql 都只定义新表结构。
-- - 本脚本带存在性判断：对已经移除该列、或从未建过该列的库可安全重复执行，不会报 1091。
-- - 同名的单列索引（如 idx_passenger_ai_message_customer）会随列一并删除，无需单独处理；
--   以 customer_id 开头的组合索引不会自动删除，见第 2 步的核对查询，残留时手工 DROP INDEX。
-- - 消息表尚未建立时不要执行本脚本，先执行 passenger_ai_conversation_patch.sql。
-- =============================================================================

USE `passenger`;

-- -----------------------------------------------------------------------------
-- 1. 核对当前状态：返回 1 行表示仍存在冗余列，返回 0 行表示已迁移，可整段跳过
-- -----------------------------------------------------------------------------
SELECT `COLUMN_NAME`, `COLUMN_TYPE`, `IS_NULLABLE`, `COLUMN_COMMENT`
FROM `information_schema`.`COLUMNS`
WHERE `TABLE_SCHEMA` = DATABASE()
  AND `TABLE_NAME` = 'passenger_ai_message'
  AND `COLUMN_NAME` = 'customer_id';

-- -----------------------------------------------------------------------------
-- 2. 核对残留索引：以 customer_id 为首列的非主键索引必须在删列前手工 DROP INDEX，
--    否则删列后索引虽然失效，仍会以空首列的形式留在表上。
-- -----------------------------------------------------------------------------
SELECT `INDEX_NAME`, `SEQ_IN_INDEX`, `COLUMN_NAME`
FROM `information_schema`.`STATISTICS`
WHERE `TABLE_SCHEMA` = DATABASE()
  AND `TABLE_NAME` = 'passenger_ai_message'
  AND `INDEX_NAME` <> 'PRIMARY'
  AND `INDEX_NAME` IN (
      SELECT `INDEX_NAME`
      FROM `information_schema`.`STATISTICS`
      WHERE `TABLE_SCHEMA` = DATABASE()
        AND `TABLE_NAME` = 'passenger_ai_message'
        AND `SEQ_IN_INDEX` = 1
        AND `COLUMN_NAME` = 'customer_id'
  )
ORDER BY `INDEX_NAME`, `SEQ_IN_INDEX`;

-- -----------------------------------------------------------------------------
-- 3. 删除冗余列：列不存在时执行空语句，重复运行安全
-- -----------------------------------------------------------------------------
SET @has_customer_id = (
    SELECT COUNT(*)
    FROM `information_schema`.`COLUMNS`
    WHERE `TABLE_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'passenger_ai_message'
      AND `COLUMN_NAME` = 'customer_id'
);

SET @drop_customer_id_sql = IF(@has_customer_id > 0,
    'ALTER TABLE `passenger_ai_message` DROP COLUMN `customer_id`',
    'SELECT ''passenger_ai_message.customer_id 已不存在，跳过'' AS skipped');

PREPARE drop_customer_id_stmt FROM @drop_customer_id_sql;
EXECUTE drop_customer_id_stmt;
DEALLOCATE PREPARE drop_customer_id_stmt;

-- -----------------------------------------------------------------------------
-- 4. 复核：应返回 0 行
-- -----------------------------------------------------------------------------
SELECT `COLUMN_NAME`
FROM `information_schema`.`COLUMNS`
WHERE `TABLE_SCHEMA` = DATABASE()
  AND `TABLE_NAME` = 'passenger_ai_message'
  AND `COLUMN_NAME` = 'customer_id';
