-- =============================================================================
-- calculate 库：全量种子（计价规则；第二节为 estimate 兜底幂等插入）
-- 依赖：已执行 capacity_seed.sql 中的公司 800001/800003
-- =============================================================================

USE `calculate`;

-- 计价规则示例数据（库：calculate；表：fare_rule）
-- 与 capacity 演示数据对齐：杭州规则→ company 800001(CP-HZ-001)；上海徐汇区→ 800003(CP-SH-001)
-- 含浙江省杭州市 / 上海市徐汇区 的有效规则及 1 条已失效规则；执行前请确认库名。

START TRANSACTION;

INSERT INTO `fare_rule` (
  `company_id`,
  `company_no`,
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
(800001, 'CP-HZ-001', '330000', '330100', 'ECONOMY', '杭州快车-默认', NOW(), NULL, 10.00, 3.00, 10, 2.20, 0.50, NULL, NULL, 0),
(800001, 'CP-HZ-001', '330000', '330100', 'COMFORT', '杭州专车-默认', NOW(), NULL, 15.00, 3.00, 10, 3.20, 0.80, NULL, 120.00, 0),
(800003, 'CP-SH-001', '310000', '310104', 'ECONOMY', '上海快车-默认', NOW(), NULL, 12.00, 2.00, 8, 2.60, 0.60, 12.00, NULL, 0),
(800001, 'CP-HZ-001', '330000', '330100', 'ECONOMY', '杭州快车-旧版（已下线）', '2024-01-01 00:00:00', '2025-12-31 23:59:59', 8.00, 3.00, 10, 2.00, 0.45, NULL, NULL, 0);

COMMIT;

-- ---------------------------------------------------------------------------
-- 计费 estimate 联调兜底（幂等：已存在相同 公司+省+市+产品线 则跳过）
-- ---------------------------------------------------------------------------
INSERT INTO `fare_rule` (
  `company_id`,
  `company_no`,
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
    800001 AS company_id,
    'CP-HZ-001' AS company_no,
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
    AND `company_id` = 800001
    AND `province_code` = '330000'
    AND `city_code` = '330100'
    AND `product_code` = 'ECONOMY'
);
