-- 乘客与第三方 OAuth2 身份绑定（微信 openid 等）；登录后仍由 BFF 签发自建 JWT。
-- 实际授权码换 token、回调处理在 passenger-api 或独立服务实现时再写入本表。
USE `passenger`;

CREATE TABLE IF NOT EXISTS `customer_oauth_binding` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT 'customer.id',
    `provider` VARCHAR(32) NOT NULL COMMENT '第三方标识，如 wechat_mp、wechat_app、alipay',
    `provider_user_id` VARCHAR(128) NOT NULL COMMENT '第三方用户唯一标识，如 openid、unionid 视 provider 而定',
    `raw_profile_json` JSON NULL COMMENT '可选：授权后拉取的用户信息快照',
    `created_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_oauth_provider_user` (`provider`, `provider_user_id`),
    KEY `idx_oauth_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客第三方账号绑定';
