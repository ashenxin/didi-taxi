-- =============================================================================
-- order 库：管理端「订单管理」联调 / 演示用假数据（可重复执行）
-- 规模：15 笔 trip_order + 对应 order_event；与 passenger_seed（乘客 10001~10003）、
--       capacity_seed（公司 800001/800003、司机 900xxx、车辆 960xxx）对齐。
-- 状态码与 TripOrderWriteService 一致：0创建 1已分配 2已接单 3到达 4行程中 5完成 6取消 7待司机确认
-- 事件类型与实现一致：ORDER_CREATED / ORDER_ASSIGNED / ORDER_ACCEPTED / ORDER_DRIVER_ARRIVED /
--   ORDER_TRIP_STARTED / ORDER_FINISHED / ORDER_CANCELLED
-- operator_type：0系统 1乘客 2司机（与代码常量一致；非表注释中的 3 系统）
-- =============================================================================

USE `order`;

SET NAMES utf8mb4;

DELETE FROM `order_event` WHERE `order_no` LIKE 'OD20260415%';
DELETE FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%';

-- 兼容早期三条示例（若仍存在则一并清理）
DELETE FROM `order_event` WHERE `order_no` IN ('OD202603240001', 'OD202603240002', 'OD202603240003');
DELETE FROM `trip_order` WHERE `order_no` IN ('OD202603240001', 'OD202603240002', 'OD202603240003');

INSERT INTO `trip_order` (
    `order_no`, `passenger_id`, `driver_id`, `car_id`, `company_id`,
    `product_code`, `province_code`, `city_code`,
    `origin_address`, `origin_lat`, `origin_lng`,
    `dest_address`, `dest_lat`, `dest_lng`,
    `status`, `estimated_amount`, `final_amount`,
    `fare_rule_id`, `fare_rule_snapshot`,
    `cancel_by`, `cancel_reason`,
    `created_at`, `assigned_at`, `accepted_at`, `arrived_at`, `started_at`, `finished_at`, `cancelled_at`,
    `is_deleted`
) VALUES
-- 1~2：杭州 · 已完单
('OD202604150001', 10001, 900001, 960001, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市西湖区天目山路266号', 30.2755000, 120.1312000,
 '杭州市滨江区江南大道588号', 30.2081000, 120.2129000,
 5, 36.80, 41.20,
 1, NULL,
 NULL, NULL,
 '2026-04-10 08:00:00', '2026-04-10 08:02:00', '2026-04-10 08:03:00', '2026-04-10 08:14:00', '2026-04-10 08:18:00', '2026-04-10 08:52:00', NULL,
 0),
('OD202604150002', 10002, 900003, 960003, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市余杭区文一西路998号', 30.2923000, 120.0048000,
 '杭州市上城区解放东路18号', 30.2480000, 120.2140000,
 5, 29.50, 30.00,
 1, NULL,
 NULL, NULL,
 '2026-04-10 10:05:00', '2026-04-10 10:06:00', '2026-04-10 10:07:00', '2026-04-10 10:18:00', '2026-04-10 10:22:00', '2026-04-10 10:48:00', NULL,
 0),
-- 3：上海 · 乘客取消（未派单成功，从 CREATED 取消）
('OD202604150003', 10003, NULL, NULL, NULL,
 'ECONOMY', '310000', '310104',
 '上海市徐汇区虹桥路1号', 31.1950000, 121.4370000,
 '上海市静安区南京西路1515号', 31.2292000, 121.4468000,
 6, 52.00, NULL,
 NULL, NULL,
 1, '行程有变，暂不出发',
 '2026-04-11 09:20:00', NULL, NULL, NULL, NULL, NULL, '2026-04-11 09:25:00',
 0),
-- 4、12：仅已创建（待派单）
('OD202604150004', 10001, NULL, NULL, NULL,
 'ECONOMY', '330000', '330100',
 '杭州市拱墅区申花路100号', 30.3100000, 120.1200000,
 '杭州市萧山区市心北路200号', 30.1850000, 120.2650000,
 0, 22.00, NULL,
 NULL, NULL,
 NULL, NULL,
 '2026-04-14 16:00:00', NULL, NULL, NULL, NULL, NULL, NULL,
 0),
('OD202604150012', 10003, NULL, NULL, NULL,
 'ECONOMY', '310000', '310104',
 '上海市徐汇区漕溪北路88号', 31.1880000, 121.4350000,
 '上海市黄浦区中山东一路1号', 31.2330000, 121.4900000,
 0, 48.00, NULL,
 NULL, NULL,
 NULL, NULL,
 '2026-04-14 17:10:00', NULL, NULL, NULL, NULL, NULL, NULL,
 0),
-- 5、13：已分配司机
('OD202604150005', 10002, 900004, 960004, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市西湖区文三路259号', 30.2800000, 120.1400000,
 '杭州市滨江区网商路699号', 30.1900000, 120.2100000,
 1, 31.00, NULL,
 1, NULL,
 NULL, NULL,
 '2026-04-12 11:00:00', '2026-04-12 11:02:00', NULL, NULL, NULL, NULL, NULL,
 0),
('OD202604150013', 10001, 900022, 960022, 800003,
 'ECONOMY', '310000', '310104',
 '上海市徐汇区龙华中路600号', 31.1780000, 121.4550000,
 '上海市长宁区延安西路2299号', 31.2080000, 121.4000000,
 1, 44.00, NULL,
 NULL, NULL,
 NULL, NULL,
 '2026-04-13 14:00:00', '2026-04-13 14:01:00', NULL, NULL, NULL, NULL, NULL,
 0),
-- 6：已接单
('OD202604150006', 10003, 900005, 960005, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市江干区新业路228号', 30.2500000, 120.2100000,
 '杭州市西湖区文二路391号', 30.2850000, 120.1300000,
 2, 26.00, NULL,
 1, NULL,
 NULL, NULL,
 '2026-04-12 15:00:00', '2026-04-12 15:01:00', '2026-04-12 15:03:00', NULL, NULL, NULL, NULL,
 0),
-- 7：司机已到达
('OD202604150007', 10001, 900006, 960006, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市西湖区学院路77号', 30.2780000, 120.1250000,
 '杭州市余杭区五常大道1号', 30.2950000, 120.0500000,
 3, 35.00, NULL,
 1, NULL,
 NULL, NULL,
 '2026-04-13 08:30:00', '2026-04-13 08:32:00', '2026-04-13 08:35:00', '2026-04-13 08:48:00', NULL, NULL, NULL,
 0),
-- 8：行程中
('OD202604150008', 10002, 900007, 960007, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市滨江区滨康路780号', 30.1820000, 120.1700000,
 '杭州市萧山区金城路358号', 30.1650000, 120.2700000,
 4, 27.50, NULL,
 1, NULL,
 NULL, NULL,
 '2026-04-14 09:00:00', '2026-04-14 09:01:30', '2026-04-14 09:03:00', '2026-04-14 09:14:00', '2026-04-14 09:18:00', NULL, NULL,
 0),
-- 9：上海 · 已完单
('OD202604150009', 10003, 900023, 960023, 800003,
 'ECONOMY', '310000', '310104',
 '上海市徐汇区漕宝路33号', 31.1720000, 121.4250000,
 '上海市静安区西藏北路198号', 31.2500000, 121.4600000,
 5, 38.60, 40.00,
 NULL, NULL,
 NULL, NULL,
 '2026-04-11 13:00:00', '2026-04-11 13:01:00', '2026-04-11 13:02:00', '2026-04-11 13:12:00', '2026-04-11 13:16:00', '2026-04-11 13:40:00', NULL,
 0),
-- 10：已分配后乘客取消
('OD202604150010', 10001, 900008, 960008, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市西湖区紫荆花路108号', 30.2700000, 120.1150000,
 '杭州市拱墅区大关路100号', 30.3050000, 120.1500000,
 6, 19.00, NULL,
 NULL, NULL,
 1, '改用其他出行方式',
 '2026-04-12 18:00:00', '2026-04-12 18:01:00', NULL, NULL, NULL, NULL, '2026-04-12 18:04:00',
 0),
-- 11：产品线 COMFORT · 已完单
('OD202604150011', 10002, 900009, 960009, 800001,
 'COMFORT', '330000', '330100',
 '杭州市西湖区曙光路120号', 30.2600000, 120.1350000,
 '杭州市滨江区闻涛路999号', 30.2000000, 120.2050000,
 5, 58.00, 62.50,
 NULL, NULL,
 NULL, NULL,
 '2026-04-09 19:00:00', '2026-04-09 19:02:00', '2026-04-09 19:04:00', '2026-04-09 19:15:00', '2026-04-09 19:20:00', '2026-04-09 19:55:00', NULL,
 0),
-- 14：杭州 · 已完单
('OD202604150014', 10003, 900010, 960010, 800001,
 'ECONOMY', '330000', '330100',
 '杭州市上城区钱江路1366号', 30.2420000, 120.2150000,
 '杭州市西湖区文一路294号', 30.2880000, 120.1280000,
 5, 33.20, 34.80,
 1, NULL,
 NULL, NULL,
 '2026-04-08 07:20:00', '2026-04-08 07:21:00', '2026-04-08 07:23:00', '2026-04-08 07:35:00', '2026-04-08 07:40:00', '2026-04-08 08:05:00', NULL,
 0),
-- 15：系统类取消（模拟待派单超时文案，仅 CREATED→CANCELLED）
('OD202604150015', 10002, NULL, NULL, NULL,
 'ECONOMY', '330000', '330100',
 '杭州市临平区迎宾路111号', 30.4200000, 120.3000000,
 '杭州市西湖区黄龙路1号', 30.2650000, 120.1380000,
 6, 45.00, NULL,
 NULL, NULL,
 3, '待派单超时无可用车辆，请稍后重试',
 '2026-04-01 10:00:00', NULL, NULL, NULL, NULL, NULL, '2026-04-01 10:06:00',
 0);

-- ---------------------------------------------------------------------------
-- 事件流水：先写 ORDER_CREATED（全部）
-- ---------------------------------------------------------------------------
INSERT INTO `order_event` (
    `order_id`, `order_no`, `event_type`, `from_status`, `to_status`,
    `operator_type`, `operator_id`, `reason_code`, `reason_desc`, `event_payload`,
    `occurred_at`, `created_at`
)
SELECT
    `id`, `order_no`, 'ORDER_CREATED', NULL, 0,
    1, `passenger_id`, NULL, NULL, NULL,
    `created_at`, `created_at`
FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%';

-- ORDER_ASSIGNED（status >= 1 且已指派）
INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`, `created_at`)
SELECT `id`, `order_no`, 'ORDER_ASSIGNED', 0, 1, 0, NULL, `assigned_at`, `assigned_at`
FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%' AND `assigned_at` IS NOT NULL;

-- ORDER_ACCEPTED
INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`, `created_at`)
SELECT `id`, `order_no`, 'ORDER_ACCEPTED', 1, 2, 2, `driver_id`, `accepted_at`, `accepted_at`
FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%' AND `accepted_at` IS NOT NULL;

-- ORDER_DRIVER_ARRIVED
INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`, `created_at`)
SELECT `id`, `order_no`, 'ORDER_DRIVER_ARRIVED', 2, 3, 2, `driver_id`, `arrived_at`, `arrived_at`
FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%' AND `arrived_at` IS NOT NULL;

-- ORDER_TRIP_STARTED
INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`, `created_at`)
SELECT `id`, `order_no`, 'ORDER_TRIP_STARTED', 3, 4, 2, `driver_id`, `started_at`, `started_at`
FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%' AND `started_at` IS NOT NULL;

-- ORDER_FINISHED
INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`, `created_at`)
SELECT `id`, `order_no`, 'ORDER_FINISHED', 4, 5, 2, `driver_id`, `finished_at`, `finished_at`
FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%' AND `finished_at` IS NOT NULL;

-- ORDER_CANCELLED（所有已取消订单）
INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `reason_code`, `reason_desc`, `event_payload`, `occurred_at`, `created_at`)
SELECT
    `id`, `order_no`, 'ORDER_CANCELLED',
    CASE
        WHEN `assigned_at` IS NULL THEN 0
        ELSE 1
    END,
    6,
    CASE WHEN `cancel_by` = 3 THEN 0 ELSE 1 END,
    CASE WHEN `cancel_by` = 3 THEN NULL ELSE `passenger_id` END,
    CASE WHEN `cancel_by` = 3 THEN 'DISPATCH_TIMEOUT' ELSE NULL END,
    `cancel_reason`,
    CASE WHEN `cancel_by` = 3 THEN '{}' ELSE '{}' END,
    `cancelled_at`, `cancelled_at`
FROM `trip_order` WHERE `order_no` LIKE 'OD20260415%' AND `cancelled_at` IS NOT NULL;
