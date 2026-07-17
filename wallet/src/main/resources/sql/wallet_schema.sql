-- =============================================================================
-- wallet 库：建表（免密支付协议 / 支付单）
-- =============================================================================
CREATE DATABASE IF NOT EXISTS `wallet` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `wallet`;

CREATE TABLE IF NOT EXISTS `wallet_auto_pay_agreement` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `channel` VARCHAR(32) NOT NULL COMMENT '支付渠道：ALIPAY/WECHAT',
    `channel_user_id` VARCHAR(128) NULL COMMENT '渠道用户ID',
    `agreement_no` VARCHAR(128) NULL COMMENT '渠道或本地免密协议号',
    `agreement_status` VARCHAR(32) NOT NULL COMMENT 'SIGNING/ACTIVE/CLOSED/FAILED',
    `is_default` INT NOT NULL DEFAULT 0 COMMENT '是否默认免密渠道：0否1是',
    `sign_scene` VARCHAR(32) NULL COMMENT '签约场景：APP/H5/MINI_PROGRAM',
    `signed_at` DATETIME NULL COMMENT '签约成功时间',
    `closed_at` DATETIME NULL COMMENT '关闭时间',
    `last_used_at` DATETIME NULL COMMENT '最近使用时间',
    `fail_reason` VARCHAR(512) NULL COMMENT '签约或关闭失败原因',
    `raw_request` JSON NULL COMMENT '渠道请求快照',
    `raw_response` JSON NULL COMMENT '渠道响应快照',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auto_pay_passenger_channel` (`passenger_id`, `channel`, `is_deleted`),
    KEY `idx_auto_pay_passenger_status` (`passenger_id`, `agreement_status`, `is_deleted`),
    KEY `idx_auto_pay_default` (`passenger_id`, `is_default`, `agreement_status`, `is_deleted`),
    KEY `idx_auto_pay_agreement_no` (`agreement_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='钱包免密支付协议';

CREATE TABLE IF NOT EXISTS `wallet_payment_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `payment_no` VARCHAR(64) NOT NULL COMMENT '支付单号',
    `order_no` VARCHAR(64) NOT NULL COMMENT '业务订单号',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `trigger_type` VARCHAR(16) NOT NULL COMMENT 'AUTO_PAY/MANUAL',
    `attempt_no` INT NOT NULL COMMENT '同一订单支付尝试序号，从1递增',
    `channel` VARCHAR(32) NOT NULL COMMENT '支付渠道：ALIPAY/WECHAT',
    `agreement_id` BIGINT NULL COMMENT '免密协议ID',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    `status` VARCHAR(32) NOT NULL COMMENT 'PAYING/CONFIRMING/SUCCESS/FAILED/CANCELLED/DUPLICATE_SUCCESS',
    `channel_request_no` VARCHAR(64) NOT NULL COMMENT '渠道请求号，一次尝试唯一',
    `channel_trade_no` VARCHAR(128) NULL COMMENT '渠道交易号',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '幂等键',
    `checkout_token_hash` VARCHAR(64) NULL COMMENT '主动支付收银台token的SHA-256',
    `checkout_token_expires_at` DATETIME NULL COMMENT '收银台token过期时间',
    `resolved_at` DATETIME NULL COMMENT '本次尝试进入终态的时间',
    `paid_at` DATETIME NULL COMMENT '支付成功时间',
    `failed_reason` VARCHAR(512) NULL COMMENT '失败原因',
    `notify_payload` JSON NULL COMMENT '渠道回调快照',
    `notify_status` VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/FAILED/SENT',
    `notify_retry_count` INT NOT NULL DEFAULT 0 COMMENT '订单结果通知重试次数',
    `notify_version` INT NOT NULL DEFAULT 0 COMMENT '支付结果通知代次，状态变化时递增',
    `next_notify_at` DATETIME NULL COMMENT '下次通知时间',
    `last_notify_error` VARCHAR(512) NULL COMMENT '最近通知错误',
    `notified_at` DATETIME NULL COMMENT '订单服务确认接收时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_payment_no` (`payment_no`),
    UNIQUE KEY `uk_wallet_payment_idempotency` (`idempotency_key`),
    UNIQUE KEY `uk_wallet_order_attempt` (`order_no`, `attempt_no`),
    UNIQUE KEY `uk_wallet_channel_request` (`channel_request_no`),
    KEY `idx_wallet_payment_order_no` (`order_no`, `status`),
    KEY `idx_wallet_payment_passenger` (`passenger_id`, `created_at`),
    KEY `idx_wallet_payment_status` (`status`, `created_at`),
    KEY `idx_wallet_payment_channel_trade` (`channel_trade_no`),
    KEY `idx_wallet_payment_notify` (`notify_status`, `next_notify_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='钱包支付单';
