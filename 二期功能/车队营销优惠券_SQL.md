# 车队营销优惠券 SQL 与迁移说明

> 本文档描述车队营销优惠券一期目标表结构与现有钱包二期优惠券表的迁移方向。
> 产品口径见《车队营销优惠券_PRD.md》，技术方案见《车队营销优惠券_TECH.md》，接口契约见《车队营销优惠券_API.md》。

## 1. 库表归属

| 库 | 表 | 说明 |
|---|---|---|
| `calculate` | `coupon_template` | 优惠券模板 |
| `calculate` | `user_coupon` | 用户持有券 |
| `calculate` | `coupon_use_record` | 用券动作流水 |
| `order` | `trip_order_settlement` | 订单结算快照 |

不新增：

- 不新增 `fleet` 库或 `fleet_id`。
- 不新增 `wallet` 优惠券表。
- 不修改 `fare_rule` 表。
- 不新增系统内发布确认/审批表。

## 2. calculate.coupon_template

目标 DDL：

```sql
CREATE TABLE IF NOT EXISTS `coupon_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `company_id` BIGINT NOT NULL COMMENT '发券车队承运单元ID，对应 capacity.company.id',
    `company_no` VARCHAR(64) NULL COMMENT '公司编号快照',
    `company_name_snapshot` VARCHAR(128) NULL COMMENT '公司名称快照',
    `team_id_snapshot` VARCHAR(64) NULL COMMENT '车队业务编码快照',
    `team_name_snapshot` VARCHAR(128) NULL COMMENT '车队名称快照',
    `name` VARCHAR(128) NOT NULL COMMENT '优惠券名称',
    `coupon_type` VARCHAR(32) NOT NULL COMMENT 'AMOUNT_OFF/PERCENT_OFF/SPECIAL',
    `threshold_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '使用门槛金额',
    `discount_amount` DECIMAL(10,2) NULL COMMENT '固定减免金额',
    `discount_rate` DECIMAL(6,4) NULL COMMENT '折扣率，如0.9000表示9折',
    `max_discount_amount` DECIMAL(10,2) NULL COMMENT '最大优惠金额',
    `city_code` VARCHAR(32) NOT NULL COMMENT '适用城市编码',
    `product_code` VARCHAR(32) NOT NULL COMMENT '适用产品线',
    `valid_start_at` DATETIME NOT NULL COMMENT '有效开始时间',
    `valid_end_at` DATETIME NOT NULL COMMENT '有效结束时间',
    `total_count` INT NOT NULL COMMENT '总发放上限',
    `received_count` INT NOT NULL DEFAULT 0 COMMENT '已领取数量',
    `used_count` INT NOT NULL DEFAULT 0 COMMENT '已核销数量',
    `per_user_limit` INT NOT NULL DEFAULT 1 COMMENT '每人限领数量',
    `issue_type` VARCHAR(32) NOT NULL DEFAULT 'LOGIN_CLAIM' COMMENT '领取方式',
    `source_type` VARCHAR(32) NOT NULL DEFAULT 'LOGIN_POPUP' COMMENT '来源入口',
    `activity_code` VARCHAR(64) NULL COMMENT '特殊活动编码',
    `rule_config` JSON NULL COMMENT '特殊规则配置',
    `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/OFFLINE',
    `created_by` BIGINT NULL COMMENT '创建后台用户ID',
    `updated_by` BIGINT NULL COMMENT '最后更新后台用户ID',
    `published_at` DATETIME NULL COMMENT '发布时间',
    `offline_at` DATETIME NULL COMMENT '下架时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否1是',
    PRIMARY KEY (`id`),
    KEY `idx_coupon_template_scope` (`company_id`, `city_code`, `product_code`, `status`, `valid_start_at`, `valid_end_at`),
    KEY `idx_coupon_template_status` (`status`, `is_deleted`),
    KEY `idx_coupon_template_activity` (`activity_code`),
    KEY `idx_coupon_template_time` (`valid_start_at`, `valid_end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车队营销优惠券模板';
```

约束说明：

- `company_id` 必填，代表发券车队。
- `total_count` 必填，防止无限发券。
- `per_user_limit` 默认 1。
- `status` 只表达系统执行状态，不表达线下商讨状态。
- `activity_code` 常规券可为空，特殊活动券再填写。

## 3. calculate.user_coupon

目标 DDL：

```sql
CREATE TABLE IF NOT EXISTS `user_coupon` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id` BIGINT NOT NULL COMMENT '优惠券模板ID',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客用户ID',
    `company_id` BIGINT NOT NULL COMMENT '发券车队承运单元ID快照',
    `company_no` VARCHAR(64) NULL COMMENT '公司编号快照',
    `company_name_snapshot` VARCHAR(128) NULL COMMENT '公司名称快照',
    `team_id_snapshot` VARCHAR(64) NULL COMMENT '车队业务编码快照',
    `team_name_snapshot` VARCHAR(128) NULL COMMENT '车队名称快照',
    `coupon_name` VARCHAR(128) NOT NULL COMMENT '优惠券名称快照',
    `coupon_type` VARCHAR(32) NOT NULL COMMENT 'AMOUNT_OFF/PERCENT_OFF/SPECIAL',
    `threshold_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '使用门槛金额快照',
    `discount_amount` DECIMAL(10,2) NULL COMMENT '固定减免金额快照',
    `discount_rate` DECIMAL(6,4) NULL COMMENT '折扣率快照',
    `max_discount_amount` DECIMAL(10,2) NULL COMMENT '最大优惠金额快照',
    `city_code` VARCHAR(32) NOT NULL COMMENT '适用城市编码快照',
    `product_code` VARCHAR(32) NOT NULL COMMENT '适用产品线快照',
    `status` VARCHAR(32) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/LOCKED/USED/EXPIRED',
    `locked_order_no` VARCHAR(64) NULL COMMENT '锁定订单号',
    `received_at` DATETIME NOT NULL COMMENT '领取时间',
    `valid_start_at` DATETIME NOT NULL COMMENT '有效开始时间',
    `valid_end_at` DATETIME NOT NULL COMMENT '有效结束时间',
    `used_at` DATETIME NULL COMMENT '核销时间',
    `rule_snapshot` JSON NULL COMMENT '领券时规则快照',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_coupon_template_passenger` (`template_id`, `passenger_id`),
    KEY `idx_user_coupon_passenger_status` (`passenger_id`, `status`, `valid_end_at`),
    KEY `idx_user_coupon_order` (`locked_order_no`),
    KEY `idx_user_coupon_scope` (`passenger_id`, `company_id`, `city_code`, `product_code`, `status`),
    KEY `idx_user_coupon_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客持有优惠券';
```

说明：

- `uk_user_coupon_template_passenger` 对应默认每人限领 1 张。
- 如果后续允许 `per_user_limit > 1`，需要改为通过领取批次号或计数逻辑控制，不能继续只用该唯一键。
- 本期确认默认每人限领 1 张，因此唯一键可接受。

## 4. calculate.coupon_use_record

目标 DDL：

```sql
CREATE TABLE IF NOT EXISTS `coupon_use_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_coupon_id` BIGINT NOT NULL COMMENT '用户券ID',
    `template_id` BIGINT NOT NULL COMMENT '模板ID',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID',
    `order_no` VARCHAR(64) NULL COMMENT '订单号',
    `action_type` VARCHAR(32) NOT NULL COMMENT 'LOCK/RELEASE/USE/REFUND_RESTORE/EXPIRE',
    `discount_amount` DECIMAL(10,2) NULL COMMENT '本动作涉及优惠金额',
    `before_status` VARCHAR(32) NULL COMMENT '动作前状态',
    `after_status` VARCHAR(32) NULL COMMENT '动作后状态',
    `reason` VARCHAR(255) NULL COMMENT '原因',
    `rule_snapshot` JSON NULL COMMENT '用券时规则快照',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_coupon_record_coupon` (`user_coupon_id`, `created_at`),
    KEY `idx_coupon_record_order` (`order_no`, `action_type`),
    KEY `idx_coupon_record_template` (`template_id`, `action_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券用券动作流水';
```

说明：

- 不作为领取流水。
- 领取事实由 `user_coupon` 表表达。
- 金额相关动作必须写入本表。

## 5. order.trip_order_settlement 补充字段

如果 `trip_order_settlement` 已存在，建议补充以下字段。

目标结构中与优惠券和服务费相关字段：

```sql
ALTER TABLE `trip_order_settlement`
    ADD COLUMN `coupon_template_id` BIGINT NULL COMMENT '本单使用优惠券模板ID' AFTER `coupon_id`,
    ADD COLUMN `coupon_company_id` BIGINT NULL COMMENT '发券车队承运单元ID快照' AFTER `coupon_template_id`,
    ADD COLUMN `coupon_company_no` VARCHAR(64) NULL COMMENT '发券公司编号快照' AFTER `coupon_company_id`,
    ADD COLUMN `coupon_company_name_snapshot` VARCHAR(128) NULL COMMENT '发券公司名称快照' AFTER `coupon_company_no`,
    ADD COLUMN `coupon_team_id_snapshot` VARCHAR(64) NULL COMMENT '发券车队业务编码快照' AFTER `coupon_company_name_snapshot`,
    ADD COLUMN `coupon_team_name_snapshot` VARCHAR(128) NULL COMMENT '发券车队名称快照' AFTER `coupon_team_id_snapshot`,
    ADD COLUMN `coupon_type` VARCHAR(32) NULL COMMENT '优惠券类型快照' AFTER `coupon_team_name_snapshot`,
    ADD COLUMN `coupon_rule_snapshot` JSON NULL COMMENT '本单用券规则快照' AFTER `coupon_type`,
    ADD COLUMN `platform_service_fee_rate` DECIMAL(6,4) NULL COMMENT '平台服务费费率' AFTER `paid_at`,
    ADD COLUMN `platform_service_fee_amount` DECIMAL(10,2) NULL COMMENT '平台服务费金额' AFTER `platform_service_fee_rate`,
    ADD COLUMN `carrier_income_amount` DECIMAL(10,2) NULL COMMENT '承运侧收入金额' AFTER `platform_service_fee_amount`,
    ADD COLUMN `settlement_snapshot` JSON NULL COMMENT '结算快照' AFTER `carrier_income_amount`;
```

建议索引：

```sql
ALTER TABLE `trip_order_settlement`
    ADD KEY `idx_settlement_coupon_template` (`coupon_template_id`),
    ADD KEY `idx_settlement_coupon_company` (`coupon_company_id`);
```

如表尚不存在，可参考钱包二期 TECH 中原始 DDL，再合并上述字段。

## 6. 现有表迁移建议

当前代码中已存在较简单的优惠券字段，需要补齐。

### 6.1 coupon_template 补字段

```sql
ALTER TABLE `coupon_template`
    ADD COLUMN `company_id` BIGINT NULL COMMENT '发券车队承运单元ID' AFTER `id`,
    ADD COLUMN `company_no` VARCHAR(64) NULL COMMENT '公司编号快照' AFTER `company_id`,
    ADD COLUMN `company_name_snapshot` VARCHAR(128) NULL COMMENT '公司名称快照' AFTER `company_no`,
    ADD COLUMN `team_id_snapshot` VARCHAR(64) NULL COMMENT '车队业务编码快照' AFTER `company_name_snapshot`,
    ADD COLUMN `team_name_snapshot` VARCHAR(128) NULL COMMENT '车队名称快照' AFTER `team_id_snapshot`,
    ADD COLUMN `received_count` INT NOT NULL DEFAULT 0 COMMENT '已领取数量' AFTER `total_count`,
    ADD COLUMN `used_count` INT NOT NULL DEFAULT 0 COMMENT '已核销数量' AFTER `received_count`,
    ADD COLUMN `per_user_limit` INT NOT NULL DEFAULT 1 COMMENT '每人限领数量' AFTER `used_count`,
    ADD COLUMN `issue_type` VARCHAR(32) NOT NULL DEFAULT 'LOGIN_CLAIM' COMMENT '领取方式' AFTER `per_user_limit`,
    ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'LOGIN_POPUP' COMMENT '来源入口' AFTER `issue_type`,
    ADD COLUMN `activity_code` VARCHAR(64) NULL COMMENT '特殊活动编码' AFTER `source_type`,
    ADD COLUMN `rule_config` JSON NULL COMMENT '特殊规则配置' AFTER `activity_code`,
    ADD COLUMN `created_by` BIGINT NULL COMMENT '创建后台用户ID' AFTER `status`,
    ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后更新后台用户ID' AFTER `created_by`,
    ADD COLUMN `published_at` DATETIME NULL COMMENT '发布时间' AFTER `updated_by`,
    ADD COLUMN `offline_at` DATETIME NULL COMMENT '下架时间' AFTER `published_at`;
```

`valid_days` 字段如仍存在：

- 固定有效期口径已经使用 `valid_start_at / valid_end_at`。
- `valid_days` 后续可废弃，不建议继续作为主要规则。

### 6.2 user_coupon 补字段

```sql
ALTER TABLE `user_coupon`
    ADD COLUMN `company_id` BIGINT NULL COMMENT '发券车队承运单元ID快照' AFTER `passenger_id`,
    ADD COLUMN `company_no` VARCHAR(64) NULL COMMENT '公司编号快照' AFTER `company_id`,
    ADD COLUMN `company_name_snapshot` VARCHAR(128) NULL COMMENT '公司名称快照' AFTER `company_no`,
    ADD COLUMN `team_id_snapshot` VARCHAR(64) NULL COMMENT '车队业务编码快照' AFTER `company_name_snapshot`,
    ADD COLUMN `team_name_snapshot` VARCHAR(128) NULL COMMENT '车队名称快照' AFTER `team_id_snapshot`,
    ADD COLUMN `coupon_type` VARCHAR(32) NULL COMMENT '优惠券类型快照' AFTER `coupon_name`,
    ADD COLUMN `discount_rate` DECIMAL(6,4) NULL COMMENT '折扣率快照' AFTER `discount_amount`,
    ADD COLUMN `max_discount_amount` DECIMAL(10,2) NULL COMMENT '最大优惠金额快照' AFTER `discount_rate`,
    ADD COLUMN `rule_snapshot` JSON NULL COMMENT '领券时规则快照' AFTER `used_at`;
```

建议补唯一键和索引：

```sql
ALTER TABLE `user_coupon`
    ADD UNIQUE KEY `uk_user_coupon_template_passenger` (`template_id`, `passenger_id`),
    ADD KEY `idx_user_coupon_scope` (`passenger_id`, `company_id`, `city_code`, `product_code`, `status`);
```

### 6.3 coupon_use_record 补字段

```sql
ALTER TABLE `coupon_use_record`
    ADD COLUMN `rule_snapshot` JSON NULL COMMENT '用券时规则快照' AFTER `reason`;
```

建议补索引：

```sql
ALTER TABLE `coupon_use_record`
    ADD KEY `idx_coupon_record_order` (`order_no`, `action_type`),
    ADD KEY `idx_coupon_record_template` (`template_id`, `action_type`, `created_at`);
```

## 7. 领取与锁券关键 SQL

领取模板扣减：

```sql
UPDATE `coupon_template`
SET `received_count` = `received_count` + 1,
    `updated_at` = NOW()
WHERE `id` = ?
  AND `status` = 'PUBLISHED'
  AND `is_deleted` = 0
  AND `valid_start_at` <= NOW()
  AND `valid_end_at` > NOW()
  AND `received_count` < `total_count`;
```

锁券：

```sql
UPDATE `user_coupon`
SET `status` = 'LOCKED',
    `locked_order_no` = ?,
    `updated_at` = NOW()
WHERE `id` = ?
  AND `passenger_id` = ?
  AND `status` = 'UNUSED'
  AND `company_id` = ?
  AND `city_code` = ?
  AND `product_code` = ?
  AND `valid_start_at` <= NOW()
  AND `valid_end_at` > NOW();
```

核销：

```sql
UPDATE `user_coupon`
SET `status` = 'USED',
    `used_at` = NOW(),
    `updated_at` = NOW()
WHERE `id` = ?
  AND `passenger_id` = ?
  AND `status` = 'LOCKED'
  AND `locked_order_no` = ?;
```

释放：

```sql
UPDATE `user_coupon`
SET `status` = 'UNUSED',
    `locked_order_no` = NULL,
    `updated_at` = NOW()
WHERE `id` = ?
  AND `passenger_id` = ?
  AND `status` = 'LOCKED'
  AND `locked_order_no` = ?;
```

## 8. 注意事项

- 目标结构已同步到 `calculate_schema.sql`、`calculate_coupon_identity_patch.sql` 与 `order_schema.sql`；已有环境仍需按当前实际 schema 选择全量初始化或增量迁移，不能重复执行冲突 DDL。
- JSON 字段要求 MySQL 5.7+；如目标环境不支持 JSON，可改为 `TEXT` 并由应用层校验。
- 当前 `company_id` 未来要设置为 NOT NULL；迁移阶段如已有历史数据，可先允许 NULL，补数据后再改约束。
- `fare_rule` 不新增字段，后台从计价规则详情进入优惠券页时只使用计价规则维度查询。
- 优惠券涉及金额，正式上线前必须补齐服务费、承运侧收入、退款、对账相关测试。
