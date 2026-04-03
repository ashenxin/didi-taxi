-- 计费 estimate 联调用示例数据（calculate 库）
-- 说明：如果你已执行 seed_fare_rules.sql，则可不执行本文件；此文件用于保证杭州(330100)+ECONOMY 一定有有效规则

USE `calculate`;

-- 仅补一条兜底规则（若已存在同 city/product 的规则，可按需删改）
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
)
SELECT *
FROM (
  SELECT
    '330000' AS province_code,
    '330100' AS city_code,
    'ECONOMY' AS product_code,
    '杭州快车-联调兜底' AS rule_name,
    NOW() AS effective_from,
    NULL AS effective_to,
    10.00 AS base_fare,
    3.00 AS included_distance_km,
    10 AS included_duration_min,
    2.20 AS per_km_price,
    0.50 AS per_minute_price,
    NULL AS minimum_fare,
    NULL AS maximum_fare,
    0 AS is_deleted
) t
WHERE NOT EXISTS (
  SELECT 1 FROM `fare_rule`
  WHERE `is_deleted` = 0
    AND `province_code` = '330000'
    AND `city_code` = '330100'
    AND `product_code` = 'ECONOMY'
);

