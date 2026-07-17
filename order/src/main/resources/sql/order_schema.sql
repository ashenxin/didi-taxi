-- =============================================================================
-- order 库：建表（trip_order + order_event；含派单确认窗口字段）
-- 种子数据见 order_seed.sql
-- =============================================================================
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

    `status` INT NOT NULL COMMENT '订单状态：0已创建 1已分配 2已接单 3司机已到达 4行程中 5已完成 6已取消 7待司机确认',

    `estimated_amount` DECIMAL(10, 2) NULL COMMENT '预估金额（下单时计价）',
    `final_amount` DECIMAL(10, 2) NULL COMMENT '最终金额（完单后计价）',

    `fare_rule_id` BIGINT NULL COMMENT '命中的计价规则ID（calculate.fare_rule.id）',
    `fare_rule_snapshot` JSON NULL COMMENT '计价关键快照（可选，防止规则变更影响历史解释）',
    `planned_distance_meters` BIGINT NULL COMMENT '下单冻结的本地mock规划距离（米）',
    `planned_duration_seconds` BIGINT NULL COMMENT '下单冻结的本地mock预计时长（秒）',
    `distance_source` VARCHAR(32) NULL COMMENT '距离来源，本期LOCAL_MOCK_ROUTE',
    `fare_calculation_version` VARCHAR(32) NULL COMMENT '计价算法版本',
    `route_mock_version` VARCHAR(32) NULL COMMENT '本地mock路线版本',
    `mock_actual_duration_seconds` BIGINT NULL COMMENT '结算首次生成并冻结的mock实际计费时长',
    `duration_source` VARCHAR(32) NULL COMMENT '时长来源，本期LOCAL_MOCK_TRIP',
    `trip_metrics_version` VARCHAR(32) NULL COMMENT 'mock实际指标生成版本',
    `blocks_new_order` TINYINT NULL COMMENT '1阻止乘客创建下一单，解除后为NULL',

    `cancel_by` INT NULL COMMENT '取消方：1乘客 2司机 3系统',
    `cancel_reason` VARCHAR(255) NULL COMMENT '取消原因',

    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `assigned_at` DATETIME NULL COMMENT '派单时间',
    `offer_expires_at` DATETIME NULL COMMENT '当前司机确认窗口截止时间（status=7 时有效）',
    `offer_round` INT NOT NULL DEFAULT 0 COMMENT '派单/确认轮次',
    `last_offer_at` DATETIME NULL COMMENT '最近一次发起确认的时间',
    `accepted_at` DATETIME NULL COMMENT '接单时间',
    `arrived_at` DATETIME NULL COMMENT '司机到达时间',
    `started_at` DATETIME NULL COMMENT '行程开始时间',
    `finished_at` DATETIME NULL COMMENT '完单时间',
    `cancelled_at` DATETIME NULL COMMENT '取消时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0未删除',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_order_order_no` (`order_no`),
    UNIQUE KEY `uk_trip_order_passenger_block` (`passenger_id`, `blocks_new_order`),

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

-- =============================================================================
-- Transactional Outbox（两段式异步派单）
-- =============================================================================
CREATE TABLE IF NOT EXISTS `order_outbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID（消息中 eventId=该ID字符串化）',
    `topic` VARCHAR(128) NOT NULL COMMENT 'Kafka topic，如 order.dispatch.requested.v1',
    `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型，如 ORDER_CREATED_NEED_DISPATCH',
    `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合ID：orderNo',
    `payload` JSON NOT NULL COMMENT '消息体 JSON（含 schemaVersion/eventId/orderNo/cityCode/productCode/origin/createdAt 等）',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/PUBLISHED/FAILED',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '发布重试次数',
    `next_retry_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    `processing_at` DATETIME NULL COMMENT '领取时间（PROCESSING）',
    `processing_by` VARCHAR(128) NULL COMMENT '领取者标识（hostname/instanceId）',
    `last_error` VARCHAR(2000) NULL COMMENT '最近一次发布失败原因（截断）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_outbox_status_next` (`status`, `next_retry_at`, `id`),
    KEY `idx_outbox_agg` (`aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单 Outbox 事件表';

-- =============================================================================
-- 请求级幂等（Idempotency-Key）
-- =============================================================================
CREATE TABLE IF NOT EXISTS `order_idempotent_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `request_id` VARCHAR(128) NOT NULL COMMENT '客户端幂等键 Idempotency-Key',
    `action_type` VARCHAR(64) NOT NULL COMMENT '动作类型，本期为 CREATE_ORDER',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID',
    `order_no` VARCHAR(64) NULL COMMENT '成功创建的订单号',
    `status` VARCHAR(32) NOT NULL COMMENT 'PROCESSING/SUCCESS/FAILED',
    `request_hash` VARCHAR(64) NOT NULL COMMENT '请求关键字段 SHA-256',
    `response_snapshot` JSON NULL COMMENT '成功响应快照，至少包含 orderNo',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_idem_request_id` (`request_id`),
    KEY `idx_order_idem_passenger` (`passenger_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单写接口幂等记录';

-- =============================================================================
-- 订单结算快照（优惠券、实付、平台服务费、承运侧收入、支付状态）
-- =============================================================================
CREATE TABLE IF NOT EXISTS `trip_order_settlement` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `estimated_amount` DECIMAL(10,2) NULL COMMENT '预估金额',
    `final_amount` DECIMAL(10,2) NULL COMMENT '最终车费',
    `coupon_id` BIGINT NULL COMMENT '本单使用的用户券ID，calculate.user_coupon.id',
    `coupon_template_id` BIGINT NULL COMMENT '本单使用优惠券模板ID',
    `coupon_company_id` BIGINT NULL COMMENT '发券车队承运单元ID快照',
    `coupon_company_no` VARCHAR(64) NULL COMMENT '发券公司编号快照',
    `coupon_company_name_snapshot` VARCHAR(128) NULL COMMENT '发券公司名称快照',
    `coupon_team_id_snapshot` VARCHAR(64) NULL COMMENT '发券车队业务编码快照',
    `coupon_team_name_snapshot` VARCHAR(128) NULL COMMENT '发券车队名称快照',
    `coupon_type` VARCHAR(32) NULL COMMENT '优惠券类型快照',
    `coupon_discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '优惠券抵扣金额',
    `coupon_rule_snapshot` JSON NULL COMMENT '本单用券规则快照',
    `payable_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '乘客应付金额',
    `platform_service_fee_rate` DECIMAL(6,4) NULL COMMENT '平台服务费费率',
    `platform_service_fee_amount` DECIMAL(10,2) NULL COMMENT '平台服务费金额',
    `carrier_income_amount` DECIMAL(10,2) NULL COMMENT '承运侧收入金额',
    `settlement_snapshot` JSON NULL COMMENT '结算快照',
    `payment_no` VARCHAR(64) NULL COMMENT '钱包支付单号',
    `payment_status` INT NOT NULL DEFAULT 0 COMMENT '支付状态：0待支付 1支付中 2成功 3失败',
    `paid_amount` DECIMAL(10,2) NULL COMMENT '已支付金额',
    `paid_at` DATETIME NULL COMMENT '支付完成时间',
    `settlement_status` VARCHAR(32) NOT NULL DEFAULT 'CALCULATING' COMMENT 'CALCULATING/PAY_CONFIRMING/PAYMENT_REQUIRED/PAID',
    `failure_code` VARCHAR(64) NULL COMMENT '结算失败码',
    `failure_summary` VARCHAR(2000) NULL COMMENT '结算失败摘要',
    `manual_action_required` TINYINT NOT NULL DEFAULT 0 COMMENT '是否需要运营人工处理',
    `version` INT NOT NULL DEFAULT 0 COMMENT 'CAS版本号',
    `settled_at` DATETIME NULL COMMENT '结算完成时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_order_settlement_order_no` (`order_no`),
    KEY `idx_settlement_passenger` (`passenger_id`, `created_at`),
    KEY `idx_settlement_coupon` (`coupon_id`),
    KEY `idx_settlement_coupon_template` (`coupon_template_id`),
    KEY `idx_settlement_coupon_company` (`coupon_company_id`),
    KEY `idx_settlement_payment_no` (`payment_no`),
    KEY `idx_settlement_status` (`settlement_status`, `payment_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单结算快照';
