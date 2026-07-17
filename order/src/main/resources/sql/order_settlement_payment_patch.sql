ALTER TABLE trip_order_settlement
    ADD COLUMN active_payment_no VARCHAR(64) NULL
        COMMENT '当前处理中支付尝试号，用于PAY_CONFIRMING原交易查询' AFTER payment_no,
    ADD KEY idx_settlement_active_payment_no (active_payment_no);
