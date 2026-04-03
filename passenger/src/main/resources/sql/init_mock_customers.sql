USE `passenger`;

DELETE FROM `customer` WHERE `id` IN (10001, 10002, 10003);

INSERT INTO `customer` (
    `id`, `phone`, `password_hash`, `nickname`, `avatar_url`,
    `status`, `real_name`, `id_card_no`, `is_deleted`
) VALUES
(10001, '13800138000', NULL, '乘客A', NULL, 0, NULL, NULL, 0),
(10002, '13900139000', NULL, '乘客B', NULL, 0, NULL, NULL, 0),
(10003, '13700137000', NULL, '乘客C', NULL, 0, NULL, NULL, 0);
