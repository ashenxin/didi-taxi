-- =============================================================================
-- calculate 库：优惠券领取身份与注销作废补丁
-- 用途：已有 calculate 库升级到“同手机号注销再注册不可重复领券”口径。
-- 注意：执行前请先通过 information_schema 检查字段/索引是否已存在，避免重复 ALTER。
-- =============================================================================

USE `calculate`;

ALTER TABLE `user_coupon`
    ADD COLUMN `claim_identity_type` VARCHAR(32) NULL COMMENT '领取身份类型：PHONE / CUSTOMER / DEVICE / REAL_NAME' AFTER `passenger_id`,
    ADD COLUMN `claim_identity_hash` VARCHAR(128) NULL COMMENT '领取身份哈希，如手机号SHA-256' AFTER `claim_identity_type`,
    ADD COLUMN `invalid_reason` VARCHAR(64) NULL COMMENT '失效原因：ACCOUNT_CANCEL / TEMPLATE_OFFLINE / RISK_CONTROL' AFTER `used_at`,
    ADD COLUMN `invalid_at` DATETIME NULL COMMENT '失效时间' AFTER `invalid_reason`;

ALTER TABLE `user_coupon`
    ADD INDEX `idx_user_coupon_claim_identity` (`template_id`, `claim_identity_type`, `claim_identity_hash`);

-- 历史数据回填后，再添加唯一约束兜底并发重复领取。
-- 添加前必须先确认下面查询无重复记录：
--
-- SELECT template_id, claim_identity_type, claim_identity_hash, COUNT(*) cnt
-- FROM user_coupon
-- WHERE claim_identity_type IS NOT NULL
--   AND claim_identity_hash IS NOT NULL
-- GROUP BY template_id, claim_identity_type, claim_identity_hash
-- HAVING cnt > 1;
--
-- ALTER TABLE `user_coupon`
--     ADD UNIQUE KEY `uk_user_coupon_template_identity`
--     (`template_id`, `claim_identity_type`, `claim_identity_hash`);
