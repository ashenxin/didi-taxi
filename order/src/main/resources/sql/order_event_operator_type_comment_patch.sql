-- =============================================================================
-- order 库：修正 order_event.operator_type 的列注释（存量库增量迁移）
--
-- 权威口径与 order-service 的 OPERATOR_* 常量一致：
--   0 系统、1 乘客、2 司机
-- 新建库由 order_schema.sql 直接生效；已经建表的存量库执行本脚本后生效。
-- 本脚本只修改列注释，不修改任何事件数据，可重复执行。
-- =============================================================================

USE `order`;

ALTER TABLE `order_event`
    MODIFY COLUMN `operator_type` INT NOT NULL
        COMMENT '操作方：0系统 1乘客 2司机';
