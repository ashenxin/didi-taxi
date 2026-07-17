ALTER TABLE wallet_payment_order
    ADD COLUMN notify_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/FAILED/SENT' AFTER notify_payload,
    ADD COLUMN notify_retry_count INT NOT NULL DEFAULT 0 COMMENT '订单结果通知重试次数' AFTER notify_status,
    ADD COLUMN notify_version INT NOT NULL DEFAULT 0 COMMENT '支付结果通知代次，状态变化时递增' AFTER notify_retry_count,
    ADD COLUMN next_notify_at DATETIME NULL COMMENT '下次通知时间' AFTER notify_version,
    ADD COLUMN last_notify_error VARCHAR(512) NULL COMMENT '最近通知错误' AFTER next_notify_at,
    ADD COLUMN notified_at DATETIME NULL COMMENT '订单服务确认接收时间' AFTER last_notify_error,
    ADD KEY idx_wallet_payment_notify (notify_status, next_notify_at);

UPDATE wallet_payment_order
SET notify_status = 'PENDING',
    notify_version = 1,
    notify_retry_count = 0,
    next_notify_at = NOW(),
    last_notify_error = NULL
WHERE status IN ('SUCCESS', 'FAILED', 'CANCELLED', 'CONFIRMING', 'DUPLICATE_SUCCESS')
  AND notify_status = 'NONE';
