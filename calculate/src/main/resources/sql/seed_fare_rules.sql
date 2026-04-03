-- 插入示例计价规则（用于后台计价管理演示）
-- 库：calculate；表：fare_rule

START TRANSACTION;

INSERT INTO `fare_rule` (
  `province_code`,
  `city_code`,
  `product_code`,
  `rule_name`,
  `effective_from`,
  `effective_to`,
  `base_fare`,
  `included_distance_km`,
  `included_duration_min`,
  `per_km_price`,
  `per_minute_price`,
  `minimum_fare`,
  `maximum_fare`,
  `is_deleted`
) VALUES
('330000','330100','ECONOMY','杭州快车-默认', NOW(), NULL, 10.00, 3.00, 10, 2.20, 0.50, NULL, NULL, 0),
('330000','330100','COMFORT','杭州专车-默认', NOW(), NULL, 15.00, 3.00, 10, 3.20, 0.80, NULL, 120.00, 0),
('310000','310100','ECONOMY','上海快车-默认', NOW(), NULL, 12.00, 2.00, 8, 2.60, 0.60, 12.00, NULL, 0);

COMMIT;

