-- 订单服务 order 库：主表 + 事件流水
CREATE DATABASE IF NOT EXISTS `order` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `order`;

CREATE TABLE IF NOT EXISTS `trip_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单号（业务唯一）',

    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID（关联 passenger.customer.id）',
    `driver_id` BIGINT NULL COMMENT '司机ID（接单后写入）',
    `car_id` BIGINT NULL COMMENT '车辆ID（接单后写入）',
    `company_id` BIGINT NULL COMMENT '运力主体ID（接单后写入）',

    `product_code` VARCHAR(64) NOT NULL COMMENT '产品线编码，如快车/专车',
    `province_code` VARCHAR(32) NOT NULL COMMENT '省份编码',
    `city_code` VARCHAR(32) NOT NULL COMMENT '城市编码',

    `origin_address` VARCHAR(255) NOT NULL COMMENT '起点地址',
    `origin_lat` DECIMAL(10, 7) NOT NULL COMMENT '起点纬度',
    `origin_lng` DECIMAL(10, 7) NOT NULL COMMENT '起点经度',

    `dest_address` VARCHAR(255) NOT NULL COMMENT '终点地址',
    `dest_lat` DECIMAL(10, 7) NOT NULL COMMENT '终点纬度',
    `dest_lng` DECIMAL(10, 7) NOT NULL COMMENT '终点经度',

    `status` INT NOT NULL COMMENT '订单状态：0已创建 1已分配 2已接单 3司机已到达 4行程中 5已完成 6已取消',

    `estimated_amount` DECIMAL(10, 2) NULL COMMENT '预估金额（下单时计价）',
    `final_amount` DECIMAL(10, 2) NULL COMMENT '最终金额（完单后计价）',

    `fare_rule_id` BIGINT NULL COMMENT '命中的计价规则ID（calculate.fare_rule.id）',
    `fare_rule_snapshot` JSON NULL COMMENT '计价关键快照（可选，防止规则变更影响历史解释）',

    `cancel_by` INT NULL COMMENT '取消方：1乘客 2司机 3系统',
    `cancel_reason` VARCHAR(255) NULL COMMENT '取消原因',

    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `assigned_at` DATETIME NULL COMMENT '派单时间',
    `accepted_at` DATETIME NULL COMMENT '接单时间',
    `arrived_at` DATETIME NULL COMMENT '司机到达时间',
    `started_at` DATETIME NULL COMMENT '行程开始时间',
    `finished_at` DATETIME NULL COMMENT '完单时间',
    `cancelled_at` DATETIME NULL COMMENT '取消时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0未删除',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_order_order_no` (`order_no`),

    KEY `idx_trip_order_passenger` (`passenger_id`),
    KEY `idx_trip_order_driver` (`driver_id`),
    KEY `idx_trip_order_status` (`status`),
    KEY `idx_trip_order_city_product` (`city_code`, `product_code`),
    KEY `idx_trip_order_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打车订单主表';

CREATE TABLE IF NOT EXISTS `order_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',

    `order_id` BIGINT NOT NULL COMMENT '订单ID（trip_order.id）',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单号冗余，便于查询',

    `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型：CREATE/ASSIGN/ACCEPT/ARRIVE/START/FINISH/CANCEL等',
    `from_status` INT NULL COMMENT '变更前状态，首个事件可空',
    `to_status` INT NULL COMMENT '变更后状态',

    `operator_type` INT NOT NULL COMMENT '操作方：1乘客 2司机 3系统 4运营',
    `operator_id` BIGINT NULL COMMENT '操作人ID（系统事件可空）',

    `reason_code` VARCHAR(64) NULL COMMENT '原因编码（如取消原因码）',
    `reason_desc` VARCHAR(255) NULL COMMENT '原因说明',

    `event_payload` JSON NULL COMMENT '事件附加数据（如位置信息、计价摘要等）',

    `occurred_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件发生时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',

    PRIMARY KEY (`id`),

    KEY `idx_order_event_order_id` (`order_id`),
    KEY `idx_order_event_order_no` (`order_no`),
    KEY `idx_order_event_event_type` (`event_type`),
    KEY `idx_order_event_occurred_at` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单事件流水';
