-- 派单确认窗口：待司机确认状态 + offer 字段（执行前请备份）
-- 状态码 7 = PENDING_DRIVER_CONFIRM（见 TripOrderWriteService 常量）

USE `order`;

ALTER TABLE `trip_order`
    ADD COLUMN `offer_expires_at` DATETIME NULL COMMENT '当前确认窗口截止时间' AFTER `assigned_at`,
    ADD COLUMN `offer_round` INT NOT NULL DEFAULT 0 COMMENT '派单/确认轮次' AFTER `offer_expires_at`,
    ADD COLUMN `last_offer_at` DATETIME NULL COMMENT '最近一次发起确认的时间' AFTER `offer_round`;

-- 原 status 注释扩展：增加 7 待司机确认
ALTER TABLE `trip_order` MODIFY COLUMN `status` INT NOT NULL COMMENT '0已创建 1已分配 2已接单 3到达 4行程中 5完成 6取消 7待司机确认';
