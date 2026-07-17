-- 将旧的一单一支付记录升级为一单多次支付尝试（MySQL 8+）。
ALTER TABLE `wallet_payment_order`
    ADD COLUMN `trigger_type` VARCHAR(16) NULL AFTER `passenger_id`,
    ADD COLUMN `attempt_no` INT NULL AFTER `trigger_type`,
    ADD COLUMN `channel_request_no` VARCHAR(64) NULL AFTER `status`,
    ADD COLUMN `checkout_token_hash` VARCHAR(64) NULL AFTER `idempotency_key`,
    ADD COLUMN `checkout_token_expires_at` DATETIME NULL AFTER `checkout_token_hash`,
    ADD COLUMN `resolved_at` DATETIME NULL AFTER `checkout_token_expires_at`;

UPDATE `wallet_payment_order` p
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY order_no ORDER BY created_at ASC, id ASC) AS generated_attempt_no
    FROM `wallet_payment_order`
) ranked ON ranked.id = p.id
SET p.trigger_type = 'AUTO_PAY',
    p.attempt_no = ranked.generated_attempt_no,
    p.channel_request_no = CONCAT('LEGACY-', p.id),
    p.resolved_at = CASE
        WHEN p.status IN ('SUCCESS', 'FAILED', 'CLOSED') THEN COALESCE(p.paid_at, p.updated_at)
        ELSE NULL
    END,
    p.status = CASE p.status
        WHEN 'PROCESSING' THEN 'PAYING'
        WHEN 'CLOSED' THEN 'CANCELLED'
        ELSE p.status
    END;

ALTER TABLE `wallet_payment_order`
    MODIFY COLUMN `trigger_type` VARCHAR(16) NOT NULL,
    MODIFY COLUMN `attempt_no` INT NOT NULL,
    MODIFY COLUMN `channel_request_no` VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY `uk_wallet_order_attempt` (`order_no`, `attempt_no`),
    ADD UNIQUE KEY `uk_wallet_channel_request` (`channel_request_no`);
