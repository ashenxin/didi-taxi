-- P3：Order 乘客账户生命周期投影与下单事务围栏
-- 上线顺序：先建表并回填 ACTIVE 投影，再启用下单硬门禁。

CREATE TABLE IF NOT EXISTS `order_account_lifecycle_projection` (
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `business_status` INT NOT NULL COMMENT '乘客业务状态快照，0正常',
    `lifecycle_status` VARCHAR(24) NOT NULL COMMENT 'ACTIVE/CANCELLING/CANCELLED',
    `lifecycle_version` BIGINT NOT NULL COMMENT 'Passenger 侧单调递增版本',
    `operation_no` VARCHAR(64) NULL COMMENT '换号/销号操作号，ACTIVE回填可空',
    `source_event_id` VARCHAR(64) NOT NULL COMMENT '来源事件ID，用于严格幂等',
    `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '本地CAS版本',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`customer_id`),
    UNIQUE KEY `uk_order_lifecycle_source_event` (`source_event_id`),
    KEY `idx_order_lifecycle_status_version` (`lifecycle_status`, `lifecycle_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客账户生命周期Order本地投影';

-- 回填语句将在 P3.4 根据 Passenger 权威库的实际连通方式生成。
-- 硬门禁启用前必须保证所有可下单乘客均已有 ACTIVE 投影；缺失投影会按 503 fail-close。
