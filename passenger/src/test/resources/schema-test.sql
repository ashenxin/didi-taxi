CREATE TABLE IF NOT EXISTS customer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone VARCHAR(32) NOT NULL,
    password_hash VARCHAR(128) NULL,
    nickname VARCHAR(64) NULL,
    avatar_url VARCHAR(512) NULL,
    status INT NOT NULL DEFAULT 0,
    lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version BIGINT NOT NULL DEFAULT 0,
    auth_epoch BIGINT NOT NULL DEFAULT 0,
    current_lifecycle_operation_no VARCHAR(64) NULL,
    cancelled_at TIMESTAMP NULL,
    real_name VARCHAR(64) NULL,
    id_card_no VARCHAR(32) NULL,
    is_deleted INT NOT NULL DEFAULT 0,
    phone_active VARCHAR(32) AS (CASE WHEN is_deleted = 0 THEN phone ELSE NULL END),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_phone_active ON customer (phone_active);

CREATE TABLE IF NOT EXISTS account_lifecycle_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_no VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    expected_lifecycle_version BIGINT NOT NULL,
    applied_lifecycle_version BIGINT NULL,
    plan_code VARCHAR(64) NOT NULL,
    plan_version INT NOT NULL,
    plan_digest CHAR(64) NOT NULL,
    irreversible_started TINYINT NOT NULL DEFAULT 0,
    restricted_auth_epoch BIGINT NULL,
    active_blocker_count INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 0,
    next_wakeup_at TIMESTAMP NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(512) NULL,
    request_context VARCHAR NULL,
    requested_at TIMESTAMP NOT NULL,
    fenced_at TIMESTAMP NULL,
    execution_started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    aborted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_customer_id BIGINT AS (
        CASE WHEN status IN ('REQUESTED', 'FENCED', 'VALIDATING', 'BLOCKED', 'EXECUTING', 'RETRY_PENDING', 'MANUAL_REVIEW')
             THEN customer_id ELSE NULL END
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_operation_no
    ON account_lifecycle_operation (operation_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_operation_idempotency
    ON account_lifecycle_operation (customer_id, operation_type, idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_operation_active_customer
    ON account_lifecycle_operation (active_customer_id);
CREATE INDEX IF NOT EXISTS idx_lifecycle_operation_status_wakeup
    ON account_lifecycle_operation (status, next_wakeup_at, id);

CREATE TABLE IF NOT EXISTS account_lifecycle_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id BIGINT NOT NULL,
    step_code VARCHAR(64) NOT NULL,
    participant_code VARCHAR(32) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    execution_mode VARCHAR(24) NOT NULL,
    criticality VARCHAR(24) NOT NULL,
    sequence_no INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 0,
    retry_initial_seconds INT NOT NULL DEFAULT 5,
    timeout_seconds INT NOT NULL DEFAULT 30,
    next_retry_at TIMESTAMP NULL,
    timeout_at TIMESTAMP NULL,
    command_event_id VARCHAR(64) NULL,
    result_event_id VARCHAR(64) NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(512) NULL,
    step_config VARCHAR NULL,
    command_snapshot VARCHAR NULL,
    result_snapshot VARCHAR NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_step_operation_code
    ON account_lifecycle_step (operation_id, step_code);
CREATE INDEX IF NOT EXISTS idx_lifecycle_step_retry
    ON account_lifecycle_step (status, next_retry_at, id);

CREATE TABLE IF NOT EXISTS account_lifecycle_blocker (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id BIGINT NOT NULL,
    step_id BIGINT NULL,
    domain_code VARCHAR(32) NOT NULL,
    blocker_key VARCHAR(191) NOT NULL,
    blocker_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NULL,
    resource_id VARCHAR(128) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    resolution_actions VARCHAR NULL,
    snapshot_json VARCHAR NULL,
    detected_at TIMESTAMP NOT NULL,
    last_confirmed_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP NULL,
    resolution_reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_blocker_stable
    ON account_lifecycle_blocker (operation_id, domain_code, blocker_key);

CREATE TABLE IF NOT EXISTS account_lifecycle_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    operation_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NULL,
    actor_type VARCHAR(24) NOT NULL,
    actor_id VARCHAR(64) NULL,
    reason_code VARCHAR(64) NULL,
    trace_id VARCHAR(64) NULL,
    payload_snapshot VARCHAR NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_event_id
    ON account_lifecycle_event (event_id);

CREATE TABLE IF NOT EXISTS account_lifecycle_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    operation_id BIGINT NULL,
    aggregate_type VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT_LIFECYCLE',
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    causation_event_id VARCHAR(64) NULL,
    trace_id VARCHAR(64) NULL,
    target_domain VARCHAR(32) NULL,
    topic VARCHAR(128) NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    payload VARCHAR NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 10,
    next_retry_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_at TIMESTAMP NULL,
    processing_by VARCHAR(128) NULL,
    published_at TIMESTAMP NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_outbox_event_id
    ON account_lifecycle_outbox (event_id);
CREATE INDEX IF NOT EXISTS idx_lifecycle_outbox_publish
    ON account_lifecycle_outbox (status, next_retry_at, id);

CREATE TABLE IF NOT EXISTS customer_phone_binding_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    binding_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    phone_ciphertext VARBINARY(512) NULL,
    phone_identity_hash CHAR(64) NOT NULL,
    hash_key_version VARCHAR(32) NOT NULL,
    change_operation_no VARCHAR(64) NULL,
    change_reason VARCHAR(64) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NULL,
    retention_until TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_customer_id BIGINT AS (CASE WHEN status = 'ACTIVE' THEN customer_id ELSE NULL END)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_phone_binding_version
    ON customer_phone_binding_history (customer_id, binding_version);
CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_phone_binding_active
    ON customer_phone_binding_history (active_customer_id);

-- AI 会话两表对齐 sql/passenger_ai_conversation_patch.sql。
-- H2 的 JSON 列会将 JDBC setString 传入的 JSON 文本编码成 JSON 字符串，因此用 TEXT
-- 加 JSON 有效性约束模拟 MySQL JSON，保持 MyBatis 读取 payload_json 的语义一致。
CREATE TABLE IF NOT EXISTS passenger_ai_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_no VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    create_idempotency_key VARCHAR(128) NOT NULL,
    scene_code VARCHAR(32) NOT NULL DEFAULT 'ROUTE_WAYPOINT',
    title VARCHAR(128) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_message_sequence BIGINT NOT NULL DEFAULT 0,
    memory_summary TEXT NULL,
    summary_through_sequence BIGINT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 0,
    active_request_no VARCHAR(64) NULL,
    active_request_started_at DATETIME(3) NULL,
    last_message_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delete_reason VARCHAR(32) NULL,
    delete_operation_no VARCHAR(64) NULL,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_passenger_ai_conversation_no
    ON passenger_ai_conversation (conversation_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_passenger_ai_conversation_create_idempotency
    ON passenger_ai_conversation (customer_id, create_idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_passenger_ai_conversation_active_request
    ON passenger_ai_conversation (active_request_no);
CREATE INDEX IF NOT EXISTS idx_passenger_ai_conversation_customer_list
    ON passenger_ai_conversation (customer_id, deleted_at, last_message_at, id);
CREATE INDEX IF NOT EXISTS idx_passenger_ai_conversation_customer_scene
    ON passenger_ai_conversation (customer_id, scene_code, created_at, id);
CREATE INDEX IF NOT EXISTS idx_passenger_ai_conversation_delete_operation
    ON passenger_ai_conversation (delete_operation_no, id);

CREATE TABLE IF NOT EXISTS passenger_ai_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_no VARCHAR(64) NOT NULL,
    conversation_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    client_message_no VARCHAR(128) NULL,
    reply_to_message_id BIGINT NULL,
    role VARCHAR(16) NOT NULL,
    message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    content TEXT NOT NULL,
    payload_json TEXT NULL CHECK (payload_json IS JSON),
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED',
    model_provider VARCHAR(32) NULL,
    model_name VARCHAR(64) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    failure_code VARCHAR(64) NULL,
    failure_message VARCHAR(512) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_passenger_ai_message_no
    ON passenger_ai_message (message_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_passenger_ai_message_sequence
    ON passenger_ai_message (conversation_id, sequence_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_passenger_ai_message_client_idempotency
    ON passenger_ai_message (conversation_id, client_message_no);
CREATE INDEX IF NOT EXISTS idx_passenger_ai_message_request
    ON passenger_ai_message (request_no, id);
CREATE INDEX IF NOT EXISTS idx_passenger_ai_message_reply
    ON passenger_ai_message (reply_to_message_id);
