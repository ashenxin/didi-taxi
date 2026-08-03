-- 状态：SUPERSEDED
-- 已由 order_trip_settlement_schema_sync_patch.sql 覆盖。
-- 仅用于追溯曾经单独执行过该补丁的环境；新环境或尚未升级的环境不要再执行本文件。

ALTER TABLE trip_order_settlement
    ADD COLUMN active_payment_no VARCHAR(64) NULL
        COMMENT '当前处理中支付尝试号，用于PAY_CONFIRMING原交易查询' AFTER payment_no,
    ADD KEY idx_settlement_active_payment_no (active_payment_no);
