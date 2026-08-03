-- Passenger 新注册账号生命周期投影初始化增量迁移。
-- 原生命周期 Outbox 只允许绑定 Operation；注册不是换号/注销 Operation，
-- 因此允许注册初始化事件不携带 operation_id。

ALTER TABLE `account_lifecycle_outbox`
    MODIFY COLUMN `operation_id` BIGINT NULL
        COMMENT 'account_lifecycle_operation.id；注册初始化事件为空';
