USE `order`;

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
('OD202603240001', 10001, 20001, 30011, 40001,
 '快车', '330000', '330100',
 '杭州市西湖区天目山路 266 号', 30.2755000, 120.1312000,
 '杭州市滨江区江陵路 1760 号', 30.2081000, 120.2129000,
 5, 36.80, 41.20,
 1, NULL,
 NULL, NULL,
 '2026-03-24 09:11:20', '2026-03-24 09:11:42', '2026-03-24 09:12:01', '2026-03-24 09:19:23', '2026-03-24 09:23:15', '2026-03-24 09:49:34', NULL,
 0),
('OD202603240002', 10002, 20015, 30020, 40001,
 '快车', '330000', '330100',
 '杭州市余杭区文一西路 998 号', 30.2923000, 120.0048000,
 '杭州市上城区解放东路 18 号', 30.2480000, 120.2140000,
 4, 28.60, NULL,
 1, NULL,
 NULL, NULL,
 '2026-03-24 10:08:05', '2026-03-24 10:08:17', '2026-03-24 10:08:32', '2026-03-24 10:16:11', '2026-03-24 10:19:50', NULL, NULL,
 0),
('OD202603240003', 10003, NULL, NULL, NULL,
 '专车', '310000', '310100',
 '上海市浦东新区世纪大道 100 号', 31.2397000, 121.4998000,
 '上海市静安区南京西路 1515 号', 31.2292000, 121.4468000,
 6, 56.00, NULL,
 2, NULL,
 1, '乘客主动取消',
 '2026-03-24 11:35:47', NULL, NULL, NULL, NULL, NULL, '2026-03-24 11:39:02',
 0);

INSERT INTO `order_event` (
    `order_id`, `order_no`, `event_type`, `from_status`, `to_status`,
    `operator_type`, `operator_id`, `reason_code`, `reason_desc`, `event_payload`, `occurred_at`
)
SELECT id, order_no, 'CREATE', NULL, 0, 1, passenger_id, NULL, NULL, NULL, created_at
FROM trip_order WHERE order_no IN ('OD202603240001', 'OD202603240002', 'OD202603240003');

INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`)
SELECT id, order_no, 'ASSIGN', 0, 1, 3, NULL, assigned_at
FROM trip_order WHERE order_no IN ('OD202603240001', 'OD202603240002') AND assigned_at IS NOT NULL;

INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`)
SELECT id, order_no, 'ACCEPT', 1, 2, 2, driver_id, accepted_at
FROM trip_order WHERE order_no IN ('OD202603240001', 'OD202603240002') AND accepted_at IS NOT NULL;

INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`)
SELECT id, order_no, 'ARRIVE', 2, 3, 2, driver_id, arrived_at
FROM trip_order WHERE order_no IN ('OD202603240001', 'OD202603240002') AND arrived_at IS NOT NULL;

INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`)
SELECT id, order_no, 'START', 3, 4, 2, driver_id, started_at
FROM trip_order WHERE order_no IN ('OD202603240001', 'OD202603240002') AND started_at IS NOT NULL;

INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `occurred_at`)
SELECT id, order_no, 'FINISH', 4, 5, 2, driver_id, finished_at
FROM trip_order WHERE order_no IN ('OD202603240001') AND finished_at IS NOT NULL;

INSERT INTO `order_event` (`order_id`, `order_no`, `event_type`, `from_status`, `to_status`, `operator_type`, `operator_id`, `reason_desc`, `occurred_at`)
SELECT id, order_no, 'CANCEL', 0, 6, 1, passenger_id, cancel_reason, cancelled_at
FROM trip_order WHERE order_no IN ('OD202603240003') AND cancelled_at IS NOT NULL;
