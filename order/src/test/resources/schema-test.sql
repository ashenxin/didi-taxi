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
