CREATE TABLE IF NOT EXISTS fare_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    company_no VARCHAR(32) NOT NULL,
    province_code VARCHAR(32) NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128) NULL,
    effective_from TIMESTAMP NOT NULL,
    effective_to TIMESTAMP NULL,
    base_fare DECIMAL(10, 2) NOT NULL,
    included_distance_km DECIMAL(10, 2) NOT NULL DEFAULT 0,
    included_duration_min INT NOT NULL DEFAULT 0,
    per_km_price DECIMAL(10, 2) NOT NULL DEFAULT 0,
    per_minute_price DECIMAL(10, 2) NOT NULL DEFAULT 0,
    minimum_fare DECIMAL(10, 2) NULL,
    maximum_fare DECIMAL(10, 2) NULL,
    is_deleted INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS benefit_sign_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    sign_date DATE NOT NULL,
    sign_year_month CHAR(6) NOT NULL,
    day_of_month TINYINT NOT NULL,
    bitmap_offset TINYINT NOT NULL,
    continuous_days INT NOT NULL DEFAULT 1,
    reward_points INT NOT NULL DEFAULT 0,
    reward_rule_code VARCHAR(64) NOT NULL,
    reward_snapshot CLOB NULL,
    points_flow_id BIGINT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'APP',
    request_id VARCHAR(64) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sign_customer_date UNIQUE (customer_id, sign_date)
);

CREATE INDEX IF NOT EXISTS idx_sign_customer_month
    ON benefit_sign_record (customer_id, sign_year_month, day_of_month);

CREATE INDEX IF NOT EXISTS idx_sign_month_date
    ON benefit_sign_record (sign_year_month, sign_date);

CREATE INDEX IF NOT EXISTS idx_sign_points_flow
    ON benefit_sign_record (points_flow_id);

CREATE TABLE IF NOT EXISTS benefit_points_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    available_points INT NOT NULL DEFAULT 0,
    total_earned_points INT NOT NULL DEFAULT 0,
    total_used_points INT NOT NULL DEFAULT 0,
    total_cleared_points INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_sign_date DATE NULL,
    last_points_flow_id BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_points_account_customer UNIQUE (customer_id)
);

CREATE INDEX IF NOT EXISTS idx_points_account_status
    ON benefit_points_account (status, updated_at);

CREATE INDEX IF NOT EXISTS idx_points_account_last_sign
    ON benefit_points_account (last_sign_date);

CREATE TABLE IF NOT EXISTS benefit_points_flow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    biz_id VARCHAR(160) NOT NULL,
    points_delta INT NOT NULL,
    balance_before INT NOT NULL,
    balance_after INT NOT NULL,
    flow_direction VARCHAR(16) NOT NULL,
    sign_record_id BIGINT NULL,
    remark VARCHAR(255) NULL,
    rule_snapshot CLOB NULL,
    request_id VARCHAR(64) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_points_flow_biz UNIQUE (customer_id, biz_type, biz_id)
);

CREATE INDEX IF NOT EXISTS idx_points_flow_customer_time
    ON benefit_points_flow (customer_id, created_at);

CREATE INDEX IF NOT EXISTS idx_points_flow_account_time
    ON benefit_points_flow (account_id, created_at);

CREATE INDEX IF NOT EXISTS idx_points_flow_sign_record
    ON benefit_points_flow (sign_record_id);

CREATE TABLE IF NOT EXISTS benefit_reconciliation_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_key CHAR(64) NOT NULL,
    issue_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    customer_id BIGINT NOT NULL,
    sign_date DATE NULL,
    year_month CHAR(6) NULL,
    reference_type VARCHAR(32) NULL,
    reference_id VARCHAR(64) NULL,
    expected_snapshot CLOB NULL,
    actual_snapshot CLOB NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    first_detected_at TIMESTAMP NOT NULL,
    last_detected_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    last_run_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_benefit_reconciliation_issue_key UNIQUE (issue_key)
);

CREATE INDEX IF NOT EXISTS idx_benefit_issue_customer_status
    ON benefit_reconciliation_issue (customer_id, status, last_detected_at);

CREATE INDEX IF NOT EXISTS idx_benefit_issue_type_status
    ON benefit_reconciliation_issue (issue_type, status, last_detected_at);

CREATE TABLE IF NOT EXISTS user_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    passenger_id BIGINT NOT NULL,
    claim_identity_type VARCHAR(32) NULL,
    claim_identity_hash VARCHAR(128) NULL,
    company_id BIGINT NOT NULL,
    company_no VARCHAR(64) NULL,
    company_name_snapshot VARCHAR(128) NULL,
    team_id_snapshot VARCHAR(64) NULL,
    team_name_snapshot VARCHAR(128) NULL,
    coupon_name VARCHAR(128) NOT NULL,
    coupon_type VARCHAR(32) NOT NULL,
    threshold_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    discount_amount DECIMAL(10,2) NULL,
    discount_rate DECIMAL(6,4) NULL,
    max_discount_amount DECIMAL(10,2) NULL,
    city_code VARCHAR(32) NOT NULL,
    product_code VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    locked_order_no VARCHAR(64) NULL,
    locked_final_amount DECIMAL(10,2) NULL,
    locked_discount_amount DECIMAL(10,2) NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_start_at TIMESTAMP NOT NULL,
    valid_end_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    invalid_reason VARCHAR(64) NULL,
    invalid_at TIMESTAMP NULL,
    rule_snapshot CLOB NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_coupon_template_passenger UNIQUE (template_id, passenger_id),
    CONSTRAINT uk_user_coupon_locked_order UNIQUE (locked_order_no)
);

CREATE INDEX IF NOT EXISTS idx_user_coupon_passenger_status
    ON user_coupon (passenger_id, status, valid_end_at);

CREATE TABLE IF NOT EXISTS coupon_use_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_coupon_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    passenger_id BIGINT NOT NULL,
    order_no VARCHAR(64) NULL,
    action_type VARCHAR(32) NOT NULL,
    discount_amount DECIMAL(10,2) NULL,
    before_status VARCHAR(32) NULL,
    after_status VARCHAR(32) NULL,
    reason VARCHAR(255) NULL,
    rule_snapshot CLOB NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_coupon_record_passenger
    ON coupon_use_record (passenger_id, created_at);

CREATE TABLE IF NOT EXISTS calculate_account_lifecycle_event_inbox (
    source_event_id VARCHAR(64) PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_calculate_lifecycle_event_customer
    ON calculate_account_lifecycle_event_inbox (customer_id, lifecycle_version);

CREATE TABLE IF NOT EXISTS calculate_account_lifecycle_projection (
    customer_id BIGINT PRIMARY KEY,
    business_status INT NOT NULL,
    lifecycle_status VARCHAR(24) NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    operation_no VARCHAR(64) NULL,
    source_event_id VARCHAR(64) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_calculate_lifecycle_projection_event UNIQUE (source_event_id)
);

CREATE INDEX IF NOT EXISTS idx_calculate_lifecycle_status_version
    ON calculate_account_lifecycle_projection (lifecycle_status, lifecycle_version);

CREATE TABLE IF NOT EXISTS calculate_lifecycle_participant_inbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_no VARCHAR(64) NOT NULL,
    step_code VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    blocker_snapshot CLOB NOT NULL,
    result_snapshot CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_calculate_lifecycle_inbox_op_step UNIQUE (operation_no, step_code)
);

CREATE INDEX IF NOT EXISTS idx_calculate_lifecycle_inbox_customer
    ON calculate_lifecycle_participant_inbox (customer_id, created_at);

CREATE INDEX IF NOT EXISTS idx_calculate_lifecycle_inbox_status
    ON calculate_lifecycle_participant_inbox (status, updated_at);
