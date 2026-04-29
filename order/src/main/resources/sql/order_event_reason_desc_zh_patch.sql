-- 将 order_event.reason_desc 中历史英文说明改为中文（Navicat / 运维一次性执行）
-- 库：order；执行前请自行备份。

USE `order`;

-- 打开确认窗口：原格式 offerSeconds=N
UPDATE `order_event`
SET `reason_desc` = CONCAT('派单确认窗口时长 ', SUBSTRING_INDEX(`reason_desc`, '=', -1), ' 秒')
WHERE `event_type` = 'ORDER_OFFER_OPENED'
  AND `reason_desc` LIKE 'offerSeconds=%';

-- 确认窗口超时打回
UPDATE `order_event`
SET `reason_desc` = '确认窗口超时，保留指派司机待改派或下一轮确认'
WHERE `reason_desc` = 'driver_id retained for reschedule';

-- 改派事件
UPDATE `order_event`
SET `reason_desc` = '运力侧改派'
WHERE `reason_desc` = 'capacity reschedule';

-- 司机拒单 / 到达前取消（若已有英文落库）
UPDATE `order_event`
SET `reason_desc` = '司机拒单'
WHERE `reason_desc` = 'driver reject';

UPDATE `order_event`
SET `reason_desc` = '司机到达前取消'
WHERE `reason_desc` = 'driver cancel before arrive';
