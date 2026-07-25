CREATE TABLE wallet_auto_pay_agreement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    passenger_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    channel_user_id VARCHAR(128),
    agreement_no VARCHAR(128),
    agreement_status VARCHAR(32) NOT NULL,
    is_default INT NOT NULL DEFAULT 0,
    sign_scene VARCHAR(32),
    signed_at TIMESTAMP,
    closed_at TIMESTAMP,
    last_used_at TIMESTAMP,
    fail_reason VARCHAR(512),
    raw_request CLOB,
    raw_response CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_auto_pay_passenger_channel UNIQUE (passenger_id, channel, is_deleted)
);

CREATE TABLE wallet_payment_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no VARCHAR(64) NOT NULL UNIQUE,
    order_no VARCHAR(64) NOT NULL,
    passenger_id BIGINT NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    attempt_no INT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    agreement_id BIGINT,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    channel_request_no VARCHAR(64) NOT NULL UNIQUE,
    channel_trade_no VARCHAR(128),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    checkout_token_hash VARCHAR(64),
    checkout_token_expires_at TIMESTAMP,
    resolved_at TIMESTAMP,
    paid_at TIMESTAMP,
    failed_reason VARCHAR(512),
    notify_payload CLOB,
    notify_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    notify_retry_count INT NOT NULL DEFAULT 0,
    notify_version INT NOT NULL DEFAULT 0,
    next_notify_at TIMESTAMP,
    last_notify_error VARCHAR(512),
    notified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wallet_order_attempt UNIQUE (order_no, attempt_no)
);

CREATE TABLE wallet_account_lifecycle_event_inbox (
    source_event_id VARCHAR(64) PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE wallet_account_lifecycle_projection (
    customer_id BIGINT PRIMARY KEY,
    business_status INT NOT NULL,
    lifecycle_status VARCHAR(24) NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    operation_no VARCHAR(64),
    source_event_id VARCHAR(64) NOT NULL UNIQUE,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE wallet_lifecycle_participant_inbox (
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
    CONSTRAINT uk_wallet_lifecycle_inbox_op_step UNIQUE (operation_no, step_code)
);

CREATE TABLE wallet_auto_pay_termination (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_no VARCHAR(64) NOT NULL,
    step_code VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    agreement_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    agreement_no_snapshot VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    channel_request_no VARCHAR(64) NOT NULL UNIQUE,
    channel_response_snapshot CLOB NOT NULL,
    manual_actor VARCHAR(64),
    manual_reason VARCHAR(512),
    manual_evidence VARCHAR(512),
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wallet_termination_op_step_agreement
        UNIQUE (operation_no, step_code, agreement_id)
);
