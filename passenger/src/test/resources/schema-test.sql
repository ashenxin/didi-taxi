CREATE TABLE IF NOT EXISTS customer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone VARCHAR(32) NOT NULL,
    password_hash VARCHAR(128) NULL,
    nickname VARCHAR(64) NULL,
    avatar_url VARCHAR(512) NULL,
    status INT NOT NULL DEFAULT 0,
    real_name VARCHAR(64) NULL,
    id_card_no VARCHAR(32) NULL,
    is_deleted INT NOT NULL DEFAULT 0,
    phone_active VARCHAR(32) AS (CASE WHEN is_deleted = 0 THEN phone ELSE NULL END),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_phone_active ON customer (phone_active);
