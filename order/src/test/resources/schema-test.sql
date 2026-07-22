CREATE TABLE IF NOT EXISTS trip_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    passenger_id BIGINT NOT NULL,
    driver_id BIGINT NULL,
    car_id BIGINT NULL,
    company_id BIGINT NULL,
    product_code VARCHAR(64) NOT NULL,
    province_code VARCHAR(32) NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    origin_address VARCHAR(255) NOT NULL,
    origin_lat DECIMAL(10, 7) NOT NULL,
    origin_lng DECIMAL(10, 7) NOT NULL,
    dest_address VARCHAR(255) NOT NULL,
    dest_lat DECIMAL(10, 7) NOT NULL,
    dest_lng DECIMAL(10, 7) NOT NULL,
    status INT NOT NULL,
    estimated_amount DECIMAL(10, 2) NULL,
    final_amount DECIMAL(10, 2) NULL,
    fare_rule_id BIGINT NULL,
    fare_rule_snapshot VARCHAR(4000) NULL,
    planned_distance_meters BIGINT NULL,
    planned_duration_seconds BIGINT NULL,
    distance_source VARCHAR(32) NULL,
    fare_calculation_version VARCHAR(32) NULL,
    route_mock_version VARCHAR(32) NULL,
    mock_actual_duration_seconds BIGINT NULL,
    duration_source VARCHAR(32) NULL,
    trip_metrics_version VARCHAR(32) NULL,
    blocks_new_order TINYINT NULL,
    cancel_by INT NULL,
    cancel_reason VARCHAR(255) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_at TIMESTAMP NULL,
    offer_expires_at TIMESTAMP NULL,
    offer_round INT NOT NULL DEFAULT 0,
    last_offer_at TIMESTAMP NULL,
    accepted_at TIMESTAMP NULL,
    arrived_at TIMESTAMP NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    cancelled_at TIMESTAMP NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_trip_order_order_no ON trip_order (order_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_trip_order_passenger_block ON trip_order (passenger_id, blocks_new_order);

CREATE TABLE IF NOT EXISTS order_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    from_status INT NULL,
    to_status INT NULL,
    operator_type INT NOT NULL,
    operator_id BIGINT NULL,
    reason_code VARCHAR(64) NULL,
    reason_desc VARCHAR(255) NULL,
    event_payload VARCHAR(4000) NULL,
    occurred_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload VARCHAR(8000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processing_at TIMESTAMP NULL,
    processing_by VARCHAR(128) NULL,
    last_error VARCHAR(2000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_next ON order_outbox_event (status, next_retry_at, id);

CREATE TABLE IF NOT EXISTS order_idempotent_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    passenger_id BIGINT NOT NULL,
    order_no VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_snapshot VARCHAR(4000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_idem_request_id ON order_idempotent_record (request_id);
CREATE INDEX IF NOT EXISTS idx_order_idem_passenger ON order_idempotent_record (passenger_id, created_at);

CREATE TABLE IF NOT EXISTS order_account_lifecycle_event_inbox (
    source_event_id VARCHAR(64) PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_order_lifecycle_event_customer
    ON order_account_lifecycle_event_inbox (customer_id, lifecycle_version);

CREATE TABLE IF NOT EXISTS order_account_lifecycle_projection (
    customer_id BIGINT PRIMARY KEY,
    business_status INT NOT NULL,
    lifecycle_status VARCHAR(24) NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    operation_no VARCHAR(64) NULL,
    source_event_id VARCHAR(64) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_lifecycle_source_event
    ON order_account_lifecycle_projection (source_event_id);
CREATE INDEX IF NOT EXISTS idx_order_lifecycle_status_version
    ON order_account_lifecycle_projection (lifecycle_status, lifecycle_version);

CREATE TABLE IF NOT EXISTS order_lifecycle_participant_inbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_no VARCHAR(64) NOT NULL,
    step_code VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    blocker_snapshot VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_lifecycle_inbox_op_step
    ON order_lifecycle_participant_inbox (operation_no, step_code);
CREATE INDEX IF NOT EXISTS idx_order_lifecycle_inbox_customer
    ON order_lifecycle_participant_inbox (customer_id, created_at);

CREATE TABLE IF NOT EXISTS trip_order_settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    passenger_id BIGINT NOT NULL,
    estimated_amount DECIMAL(10, 2) NULL,
    final_amount DECIMAL(10, 2) NULL,
    coupon_id BIGINT NULL,
    coupon_template_id BIGINT NULL,
    coupon_company_id BIGINT NULL,
    coupon_company_no VARCHAR(64) NULL,
    coupon_company_name_snapshot VARCHAR(128) NULL,
    coupon_team_id_snapshot VARCHAR(64) NULL,
    coupon_team_name_snapshot VARCHAR(128) NULL,
    coupon_type VARCHAR(32) NULL,
    coupon_discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    coupon_rule_snapshot VARCHAR(4000) NULL,
    payable_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    platform_service_fee_rate DECIMAL(6, 4) NULL,
    platform_service_fee_amount DECIMAL(10, 2) NULL,
    carrier_income_amount DECIMAL(10, 2) NULL,
    settlement_snapshot VARCHAR(8000) NULL,
    payment_no VARCHAR(64) NULL,
    active_payment_no VARCHAR(64) NULL,
    payment_status INT NOT NULL DEFAULT 0,
    paid_amount DECIMAL(10, 2) NULL,
    paid_at TIMESTAMP NULL,
    settlement_status VARCHAR(32) NOT NULL DEFAULT 'CALCULATING',
    failure_code VARCHAR(64) NULL,
    failure_summary VARCHAR(2000) NULL,
    manual_action_required TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    settled_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_trip_order_settlement_order_no ON trip_order_settlement (order_no);
