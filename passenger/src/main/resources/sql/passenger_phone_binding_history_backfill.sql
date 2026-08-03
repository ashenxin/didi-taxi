-- Passenger 活跃账号手机号绑定历史补录。
--
-- 适用场景：生命周期表上线后、注册流程补齐前创建的账号，customer 已存在，
-- 但 customer_phone_binding_history 没有 ACTIVE 记录，导致换号无法结束旧绑定。
-- 本脚本可重复执行；只为当前仍有效且缺少 ACTIVE 绑定的账号补一条记录。

INSERT INTO `customer_phone_binding_history` (
    `customer_id`, `binding_version`, `status`, `phone_ciphertext`,
    `phone_identity_hash`, `hash_key_version`, `change_operation_no`,
    `change_reason`, `valid_from`, `valid_to`, `retention_until`,
    `created_at`, `updated_at`
)
SELECT
    c.`id`,
    COALESCE((
        SELECT MAX(hv.`binding_version`)
        FROM `customer_phone_binding_history` hv
        WHERE hv.`customer_id` = c.`id`
    ), 0) + 1,
    'ACTIVE',
    CONVERT(c.`phone` USING binary),
    LOWER(SHA2(c.`phone`, 256)),
    'legacy-v1',
    NULL,
    CASE WHEN EXISTS (
        SELECT 1
        FROM `customer_phone_binding_history` hh
        WHERE hh.`customer_id` = c.`id`
    ) THEN 'MIGRATION' ELSE 'REGISTER' END,
    COALESCE(c.`created_at`, c.`updated_at`, CURRENT_TIMESTAMP),
    NULL,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM `customer` c
WHERE c.`is_deleted` = 0
  AND c.`lifecycle_status` = 'ACTIVE'
  AND c.`phone` IS NOT NULL
  AND c.`phone` <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM `customer_phone_binding_history` ha
      WHERE ha.`customer_id` = c.`id`
        AND ha.`status` = 'ACTIVE'
  );

-- 核验：结果应为 0 行。
SELECT c.`id`, c.`phone`, c.`lifecycle_status`
FROM `customer` c
WHERE c.`is_deleted` = 0
  AND c.`lifecycle_status` = 'ACTIVE'
  AND c.`phone` IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM `customer_phone_binding_history` h
      WHERE h.`customer_id` = c.`id`
        AND h.`status` = 'ACTIVE'
  );
