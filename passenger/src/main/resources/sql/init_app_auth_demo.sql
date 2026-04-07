-- 可选：为演示账号设置密码登录（明文密码 password，对应 BCrypt 摘要如下）
USE `passenger`;

UPDATE `customer`
SET `password_hash` = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
WHERE `phone` = '13800138000' AND `is_deleted` = 0;
