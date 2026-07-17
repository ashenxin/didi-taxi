-- =============================================================================
-- calculate 库：建表（fare_rule / coupon_template / user_coupon / coupon_use_record / benefit_*）
-- 种子数据见 calculate_seed.sql
-- =============================================================================
-- 业务唯一维度：运力公司 + 省 + 市 + 产品线（product_code）；同一维度下生效区间不可重叠（由计费服务校验）
CREATE DATABASE IF NOT EXISTS `calculate` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `calculate`;

CREATE TABLE IF NOT EXISTS `fare_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',

    `company_id` BIGINT NOT NULL COMMENT '运力公司主键 capacity.company.id',
    `company_no` VARCHAR(32) NOT NULL COMMENT '运力公司编号，与 capacity.company.company_no 一致',

    `province_code` VARCHAR(32) NOT NULL COMMENT '省份编码',
    `city_code` VARCHAR(32) NOT NULL COMMENT '城市编码',
    `product_code` VARCHAR(64) NOT NULL COMMENT '产品线/车型档编码，与订单、运力侧约定一致',

    `rule_name` VARCHAR(128) NULL COMMENT '规则名称，便于运营识别',

    `effective_from` DATETIME NOT NULL COMMENT '生效开始时间',
    `effective_to` DATETIME NULL COMMENT '生效结束时间，NULL 表示当前仍有效',

    `base_fare` DECIMAL(10, 2) NOT NULL COMMENT '起步价',
    `included_distance_km` DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '起步含里程（公里）',
    `included_duration_min` INT NOT NULL DEFAULT 0 COMMENT '起步含时长（分钟）',
    `per_km_price` DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '超出后每公里单价',
    `per_minute_price` DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '超出后每分钟单价',
    `minimum_fare` DECIMAL(10, 2) NULL COMMENT '最低消费，NULL 表示不启用',
    `maximum_fare` DECIMAL(10, 2) NULL COMMENT '封顶价，NULL 表示不启用',

    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0 未删除，非 0 已删除',

    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    KEY `idx_fare_rule_company_scope` (`company_id`, `province_code`, `city_code`, `product_code`),
    KEY `idx_fare_rule_company_id` (`company_id`),
    KEY `idx_fare_rule_province_city_product` (`province_code`, `city_code`, `product_code`),
    KEY `idx_fare_rule_city_product` (`city_code`, `product_code`),
    KEY `idx_fare_rule_effective` (`effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计价规则';

-- =============================================================================
-- 车队营销优惠券模板
-- =============================================================================
CREATE TABLE IF NOT EXISTS `coupon_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `company_id` BIGINT NOT NULL COMMENT '发券车队承运单元ID，对应 capacity.company.id',
    `company_no` VARCHAR(64) NULL COMMENT '公司编号快照',
    `company_name_snapshot` VARCHAR(128) NULL COMMENT '公司名称快照',
    `team_id_snapshot` VARCHAR(64) NULL COMMENT '车队业务编码快照',
    `team_name_snapshot` VARCHAR(128) NULL COMMENT '车队名称快照',
    `name` VARCHAR(128) NOT NULL COMMENT '优惠券名称',
    `coupon_type` VARCHAR(32) NOT NULL COMMENT 'AMOUNT_OFF/PERCENT_OFF/SPECIAL',
    `threshold_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '使用门槛金额',
    `discount_amount` DECIMAL(10,2) NULL COMMENT '固定减免金额',
    `discount_rate` DECIMAL(6,4) NULL COMMENT '折扣率，如0.9000表示9折',
    `max_discount_amount` DECIMAL(10,2) NULL COMMENT '最大优惠金额',
    `city_code` VARCHAR(32) NOT NULL COMMENT '适用城市编码',
    `product_code` VARCHAR(32) NOT NULL COMMENT '适用产品线',
    `valid_days` INT NULL COMMENT '领取后有效天数预留；当前固定有效期以 valid_start_at/valid_end_at 为准',
    `valid_start_at` DATETIME NOT NULL COMMENT '有效开始时间',
    `valid_end_at` DATETIME NOT NULL COMMENT '有效结束时间',
    `total_count` INT NOT NULL COMMENT '总发放上限',
    `received_count` INT NOT NULL DEFAULT 0 COMMENT '已领取数量',
    `used_count` INT NOT NULL DEFAULT 0 COMMENT '已核销数量',
    `per_user_limit` INT NOT NULL DEFAULT 1 COMMENT '每人限领数量；当前代码仅支持1',
    `issue_type` VARCHAR(32) NOT NULL DEFAULT 'LOGIN_POPUP' COMMENT '发放方式/领取方式，如 LOGIN_POPUP',
    `source_type` VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '来源类型，如 NORMAL/ACTIVITY/SIGN_IN',
    `activity_code` VARCHAR(64) NULL COMMENT '活动编码',
    `rule_config` JSON NULL COMMENT '特殊规则配置',
    `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/OFFLINE',
    `created_by` BIGINT NULL COMMENT '创建后台用户ID',
    `updated_by` BIGINT NULL COMMENT '最后更新后台用户ID',
    `published_at` DATETIME NULL COMMENT '发布时间',
    `offline_at` DATETIME NULL COMMENT '下架时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0未删除',
    PRIMARY KEY (`id`),
    KEY `idx_coupon_template_scope` (`company_id`, `city_code`, `product_code`, `status`, `valid_start_at`, `valid_end_at`),
    KEY `idx_coupon_template_status` (`status`, `is_deleted`),
    KEY `idx_coupon_template_activity` (`activity_code`),
    KEY `idx_coupon_template_time` (`valid_start_at`, `valid_end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车队营销优惠券模板';

-- =============================================================================
-- 乘客持有优惠券
-- =============================================================================
CREATE TABLE IF NOT EXISTS `user_coupon` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id` BIGINT NOT NULL COMMENT '优惠券模板ID',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `claim_identity_type` VARCHAR(32) NULL COMMENT '领取身份类型：PHONE / CUSTOMER / DEVICE / REAL_NAME',
    `claim_identity_hash` VARCHAR(128) NULL COMMENT '领取身份哈希，如手机号SHA-256',
    `company_id` BIGINT NOT NULL COMMENT '发券车队承运单元ID快照',
    `company_no` VARCHAR(64) NULL COMMENT '公司编号快照',
    `company_name_snapshot` VARCHAR(128) NULL COMMENT '公司名称快照',
    `team_id_snapshot` VARCHAR(64) NULL COMMENT '车队业务编码快照',
    `team_name_snapshot` VARCHAR(128) NULL COMMENT '车队名称快照',
    `coupon_name` VARCHAR(128) NOT NULL COMMENT '优惠券名称快照',
    `coupon_type` VARCHAR(32) NOT NULL COMMENT 'AMOUNT_OFF/PERCENT_OFF/SPECIAL',
    `threshold_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '使用门槛金额快照',
    `discount_amount` DECIMAL(10,2) NULL COMMENT '固定减免金额快照',
    `discount_rate` DECIMAL(6,4) NULL COMMENT '折扣率快照',
    `max_discount_amount` DECIMAL(10,2) NULL COMMENT '最大优惠金额快照',
    `city_code` VARCHAR(32) NOT NULL COMMENT '适用城市编码快照',
    `product_code` VARCHAR(32) NOT NULL COMMENT '适用产品线快照',
    `status` VARCHAR(32) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/LOCKED/USED/EXPIRED/INVALID',
    `locked_order_no` VARCHAR(64) NULL COMMENT '锁定订单号',
    `locked_final_amount` DECIMAL(10,2) NULL COMMENT '锁券时最终车费快照',
    `locked_discount_amount` DECIMAL(10,2) NULL COMMENT '锁券时实际优惠金额快照',
    `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    `valid_start_at` DATETIME NOT NULL COMMENT '有效开始时间',
    `valid_end_at` DATETIME NOT NULL COMMENT '有效结束时间',
    `used_at` DATETIME NULL COMMENT '核销时间',
    `invalid_reason` VARCHAR(64) NULL COMMENT '失效原因：ACCOUNT_CANCEL / TEMPLATE_OFFLINE / RISK_CONTROL',
    `invalid_at` DATETIME NULL COMMENT '失效时间',
    `rule_snapshot` JSON NULL COMMENT '领券时规则快照',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_coupon_template_passenger` (`template_id`, `passenger_id`),
    UNIQUE KEY `uk_user_coupon_template_identity` (`template_id`, `claim_identity_type`, `claim_identity_hash`),
    KEY `idx_user_coupon_passenger_status` (`passenger_id`, `status`, `valid_end_at`),
    UNIQUE KEY `uk_user_coupon_locked_order` (`locked_order_no`),
    KEY `idx_user_coupon_scope` (`passenger_id`, `company_id`, `city_code`, `product_code`, `status`),
    KEY `idx_user_coupon_template` (`template_id`),
    KEY `idx_user_coupon_claim_identity` (`template_id`, `claim_identity_type`, `claim_identity_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客持有优惠券';

-- =============================================================================
-- 优惠券用券动作流水
-- =============================================================================
CREATE TABLE IF NOT EXISTS `coupon_use_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_coupon_id` BIGINT NOT NULL COMMENT '用户券ID',
    `template_id` BIGINT NOT NULL COMMENT '模板ID',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID',
    `order_no` VARCHAR(64) NULL COMMENT '订单号',
    `action_type` VARCHAR(32) NOT NULL COMMENT 'LOCK/RELEASE/USE/REFUND_RESTORE/EXPIRE/INVALIDATE',
    `discount_amount` DECIMAL(10,2) NULL COMMENT '本动作涉及优惠金额',
    `before_status` VARCHAR(32) NULL COMMENT '动作前状态',
    `after_status` VARCHAR(32) NULL COMMENT '动作后状态',
    `reason` VARCHAR(255) NULL COMMENT '原因',
    `rule_snapshot` JSON NULL COMMENT '用券时规则快照',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_coupon_record_coupon` (`user_coupon_id`, `created_at`),
    KEY `idx_coupon_record_order` (`order_no`, `action_type`),
    KEY `idx_coupon_record_template` (`template_id`, `action_type`, `created_at`),
    KEY `idx_coupon_record_passenger` (`passenger_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券用券动作流水';

-- =============================================================================
-- 乘客福利签到记录
-- =============================================================================
CREATE TABLE IF NOT EXISTS `benefit_sign_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `sign_date` DATE NOT NULL COMMENT '签到日期，业务时区 Asia/Shanghai',
    `sign_year_month` CHAR(6) NOT NULL COMMENT '签到年月 yyyyMM',
    `day_of_month` TINYINT NOT NULL COMMENT '自然月日期，1-28 可领奖签到',
    `bitmap_offset` TINYINT NOT NULL COMMENT 'Redis Bitmap offset，day_of_month - 1',
    `continuous_days` INT NOT NULL DEFAULT 1 COMMENT '当月连续签到天数，月初重新计算',
    `reward_points` INT NOT NULL DEFAULT 0 COMMENT '本次签到总奖励积分',
    `reward_rule_code` VARCHAR(64) NOT NULL COMMENT '命中奖励规则编码，如 SIGN_IN_DAILY/SIGN_IN_CONTINUOUS_7',
    `reward_snapshot` JSON NULL COMMENT '本次签到奖励规则快照',
    `points_flow_id` BIGINT NULL COMMENT '关联积分流水ID，写入流水后回填',
    `source_type` VARCHAR(32) NOT NULL DEFAULT 'APP' COMMENT '签到来源：APP/ADMIN_REPAIR/SYSTEM_REPAIR',
    `request_id` VARCHAR(64) NULL COMMENT '请求ID，便于排查幂等与链路',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sign_customer_date` (`customer_id`, `sign_date`),
    KEY `idx_sign_customer_month` (`customer_id`, `sign_year_month`, `day_of_month`),
    KEY `idx_sign_month_date` (`sign_year_month`, `sign_date`),
    KEY `idx_sign_points_flow` (`points_flow_id`),
    KEY `idx_sign_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客福利签到记录';

-- =============================================================================
-- 乘客福利积分账户
-- =============================================================================
CREATE TABLE IF NOT EXISTS `benefit_points_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `available_points` INT NOT NULL DEFAULT 0 COMMENT '当前可用积分',
    `total_earned_points` INT NOT NULL DEFAULT 0 COMMENT '历史累计获得积分',
    `total_used_points` INT NOT NULL DEFAULT 0 COMMENT '历史累计消耗积分；第一期不做兑换，默认0',
    `total_cleared_points` INT NOT NULL DEFAULT 0 COMMENT '历史累计清零积分，主要用于注销',
    `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CANCELLED/FROZEN',
    `last_sign_date` DATE NULL COMMENT '最近一次签到日期',
    `last_points_flow_id` BIGINT NULL COMMENT '最近一笔积分流水ID',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_account_customer` (`customer_id`),
    KEY `idx_points_account_status` (`status`, `updated_at`),
    KEY `idx_points_account_last_sign` (`last_sign_date`),
    KEY `idx_points_account_last_flow` (`last_points_flow_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客福利积分账户';

-- =============================================================================
-- 乘客福利积分流水
-- =============================================================================
CREATE TABLE IF NOT EXISTS `benefit_points_flow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `account_id` BIGINT NOT NULL COMMENT '积分账户ID',
    `biz_type` VARCHAR(32) NOT NULL COMMENT '业务类型：SIGN_IN_DAILY/SIGN_IN_CONTINUOUS_7/ACCOUNT_CANCEL_CLEAR',
    `biz_id` VARCHAR(64) NOT NULL COMMENT '业务ID：签到记录ID或注销业务号',
    `points_delta` INT NOT NULL COMMENT '积分变化，正数增加，负数扣减/清零',
    `balance_before` INT NOT NULL COMMENT '变更前可用积分',
    `balance_after` INT NOT NULL COMMENT '变更后可用积分',
    `flow_direction` VARCHAR(16) NOT NULL COMMENT '方向：IN/OUT',
    `sign_record_id` BIGINT NULL COMMENT '签到记录ID，签到类流水必填',
    `remark` VARCHAR(255) NULL COMMENT '备注',
    `rule_snapshot` JSON NULL COMMENT '规则快照或清零上下文',
    `request_id` VARCHAR(64) NULL COMMENT '请求ID，便于排查幂等与链路',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_flow_biz` (`customer_id`, `biz_type`, `biz_id`),
    KEY `idx_points_flow_customer_time` (`customer_id`, `created_at`),
    KEY `idx_points_flow_account_time` (`account_id`, `created_at`),
    KEY `idx_points_flow_sign_record` (`sign_record_id`),
    KEY `idx_points_flow_biz_type` (`biz_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客福利积分流水';
