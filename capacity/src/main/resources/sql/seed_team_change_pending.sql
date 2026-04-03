-- 插入 3 条「待审核」换队申请（演示菜单角标 / 列表 / 审核）
--
-- 前置：已存在 driver_team_change_request 表；库中有公司 1001–1005、司机 80001+（与 init_mock_capacity 一致）
--
START TRANSACTION;

DELETE FROM `driver_team_change_request`
WHERE `driver_id` IN (80001, 80002, 80003) AND `status` = 'PENDING';

UPDATE `driver`
SET `company_id` = NULL,
    `can_accept_order` = 0,
    `updated_at`     = NOW()
WHERE `id` IN (80001, 80002, 80003);

INSERT INTO `driver_team_change_request` (
    `driver_id`,
    `from_company_id`,
    `to_company_id`,
    `status`,
    `request_reason`,
    `requested_by`,
    `requested_at`,
    `is_deleted`
) VALUES
(80001, 1001, 1002, 'PENDING', '希望加入杭州二队，接单区域更顺路', 'seed:driver:80001', NOW(), 0),
(80002, 1001, 1003, 'PENDING', '想转上海运力，家里搬去上海', 'seed:driver:80002', DATE_SUB(NOW(), INTERVAL 2 HOUR), 0),
(80003, 1002, 1001, 'PENDING', '申请回到杭州一队', 'seed:driver:80003', DATE_SUB(NOW(), INTERVAL 1 DAY), 0);

COMMIT;
