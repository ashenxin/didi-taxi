# 乘客端个人中心「我的钱包：免密支付与优惠券」二期 TECH

> 本文档描述「我的钱包」中免密支付与优惠券的技术设计、库表归属、数据一致性和 SQL 草案。
> 产品口径见《乘客端_个人中心_我的钱包_免密支付与优惠券_PRD.md》，接口契约见《乘客端_个人中心_我的钱包_免密支付与优惠券_API.md》。
> 车队营销优惠券目标表结构、后台接口和规则计算以《车队营销优惠券_TECH.md》《车队营销优惠券_API.md》《车队营销优惠券_SQL.md》为准；本文保留钱包侧接入说明。

---

## 1. 实现边界

- `passenger-api` 作为乘客端 BFF，对外提供钱包页面接口。
- `wallet` 库承载免密支付授权协议、支付单，后续可独立为 `wallet-service`。
- `calculate` 库承载优惠券模板、用户券、优惠券使用流水；车队营销优惠券是独立计价营销能力，不属于 `wallet`。
- `order` 库承载订单主表、订单事件、订单结算快照表。
- 本期不实现银行卡、借钱、车险真实业务，只保留入口。

---

## 2. 技术讨论定版

- 当前只做支付宝/微信免密支付，不单独拆 `wallet_payment_method`。
- 使用 `wallet_auto_pay_agreement` 同时表达“用户支付方式”和“渠道免密协议”。
- 支付单独立为 `wallet_payment_order`，不要和免密协议表混在一起。
- 优惠券相关表放入 `calculate`，由 `calculate` 承担价格中心职责；目标结构以《车队营销优惠券_SQL.md》为准。
- `trip_order` 已有字段不迁移，后续也不继续追加支付/优惠字段。
- 新增 `trip_order_settlement` 承载订单结算、优惠、支付状态快照。
- `fare_rule_snapshot` 保持计价快照语义，不塞优惠券明细。
- `fare_rule` 不新增优惠券字段，后台从计价规则详情页按 `company_id + city_code + product_code` 查看优惠券方案。
- 平台服务费按 `payable_amount * 5%` 计算，承运侧收入为 `payable_amount - platform_service_fee_amount`。

---

## 3. 数据模型

### 3.1 wallet_auto_pay_agreement

用于记录乘客在支付宝/微信侧开通的免密授权关系。

关键字段：

- `passenger_id`：乘客 ID。
- `channel`：`ALIPAY` / `WECHAT`。
- `agreement_no`：渠道侧免密协议号。
- `agreement_status`：`SIGNING / ACTIVE / CLOSED / FAILED`。
- `is_default`：是否默认免密渠道。

约束：

- 同一乘客同一渠道只保留一条未删除记录。
- 同一乘客只能有一个默认且有效的免密渠道，前期由业务逻辑保证。

### 3.2 wallet_payment_order

用于记录单次支付交易。

关键字段：

- `payment_no`：平台支付单号。
- `order_no`：业务订单号。
- `agreement_id`：本次扣款使用的免密协议。
- `amount`：本次支付金额。
- `status`：`CREATED / PAYING / SUCCESS / FAILED / CLOSED`。
- `idempotency_key`：支付幂等键。

### 3.3 calculate 优惠券表

- `coupon_template`：优惠券模板。
- `user_coupon`：用户持有的券，保存模板关键字段快照。
- `coupon_use_record`：优惠券状态变更流水。
- 车队营销优惠券目标字段需支持 `company_id`、车队快照、`coupon_type`、`discount_rate`、`max_discount_amount`、`per_user_limit`、`activity_code`、`rule_config`、`rule_snapshot` 等，详见《车队营销优惠券_SQL.md》。

用户券状态：

- `UNUSED`：未使用。
- `LOCKED`：已被订单锁定。
- `USED`：已核销。
- `EXPIRED`：已过期。
- `INVALID`：已作废。

### 3.4 trip_order_settlement

用于承载订单结算、优惠、支付状态快照，避免 `trip_order` 持续膨胀。

关键字段：

- `order_no`：订单号，唯一。
- `estimated_amount`：下单预估价快照。
- `final_amount`：完单后最终车费快照，优惠前金额。
- `coupon_id`：本单使用的用户券 ID。
- `coupon_discount_amount`：优惠券抵扣金额。
- `payable_amount`：应付金额。
- `platform_service_fee_rate`：平台服务费费率，本期 0.0500。
- `platform_service_fee_amount`：平台服务费金额。
- `carrier_income_amount`：承运侧收入。
- `payment_no`：支付单号。
- `payment_status`：订单结算视角支付状态。
- `settlement_status`：结算状态。

---

## 4. 核心流程

### 4.1 开通免密支付

1. `passenger-api` 校验登录态，获取 `customerId`。
2. 前端选择 `ALIPAY` 或 `WECHAT`。
3. 创建或更新 `wallet_auto_pay_agreement` 为 `SIGNING`。
4. 调用第三方渠道创建免密签约请求。
5. 用户在渠道侧确认授权。
6. 渠道回调或前端轮询授权结果。
7. 更新协议状态为 `ACTIVE / FAILED`。
8. 若是用户首个有效免密渠道，可设置 `is_default=1`。

### 4.2 设置默认免密渠道

1. 校验协议属于当前乘客。
2. 校验协议状态为 `ACTIVE`。
3. 同一事务内将该乘客其他协议 `is_default=0`。
4. 将目标协议 `is_default=1`。

### 4.3 完单结算与自动扣款

1. 订单完单，`trip_order.final_amount` 已写入。
2. order 确认最终承运 `company_id`，不能使用下单预估阶段候选司机 `company_id` 作为最终用券依据。
3. `calculate` 按最终 `company_id + city_code + product_code + final_amount` 查询并锁定可用优惠券。
4. 写入或更新 `trip_order_settlement`：
   - `final_amount`
   - `coupon_id`
   - `coupon_discount_amount`
   - `payable_amount`
   - `platform_service_fee_rate`
   - `platform_service_fee_amount`
   - `carrier_income_amount`
5. `wallet` 查询默认 `ACTIVE` 免密协议。
6. 创建 `wallet_payment_order`，状态为 `CREATED`。
7. 更新 `trip_order_settlement.payment_no` 与 `payment_status=1`。
8. 发起渠道免密扣款。
9. 支付成功：
   - `wallet_payment_order.status=SUCCESS`
   - `trip_order_settlement.payment_status=2`
   - `trip_order_settlement.settlement_status=PAID`
   - `calculate.user_coupon.status=USED`
10. 支付失败：
   - `wallet_payment_order.status=FAILED`
   - `trip_order_settlement.payment_status=3`
   - 优惠券立即释放。

---

## 5. 一致性与幂等

### 5.1 支付幂等

- `wallet_payment_order.payment_no` 全局唯一。
- `wallet_payment_order.idempotency_key` 唯一。
- 同一订单同一结算阶段重复请求时，应返回已有支付单。
- 渠道侧请求也必须携带渠道幂等请求号。

### 5.2 优惠券锁定幂等

锁券必须使用条件更新：

```sql
UPDATE user_coupon
SET status = 'LOCKED', locked_order_no = ?
WHERE id = ? AND passenger_id = ? AND status = 'UNUSED';
```

受影响行数为 1 才表示锁券成功。

### 5.3 跨库一致性

- 单库内使用本地事务。
- 跨库通过状态机、幂等接口和补偿任务兜底。
- 支付回调必须可重复处理。
- 后续可增加结算对账任务，扫描 `PAYING / FAILED` 状态进行补偿。

---

## 6. fare_rule_snapshot 使用

`trip_order.fare_rule_snapshot` 后续在完单计价时写入计价快照，用于解释历史订单金额，不承载优惠券明细。

示例：

```json
{
  "ruleId": 3,
  "ruleVersion": 7,
  "productCode": "ECONOMY",
  "cityCode": "330100",
  "baseFare": 12.00,
  "baseDistanceKm": 3.00,
  "distanceFare": 18.50,
  "timeFare": 8.00,
  "nightFee": 0.00,
  "longDistanceFee": 0.00,
  "estimatedAmount": 35.00,
  "finalAmount": 38.50,
  "calculatedAt": "2026-07-04T15:20:00+08:00"
}
```

---

## 7. SQL 草案

### 7.1 wallet 库

```sql
CREATE DATABASE IF NOT EXISTS `wallet` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `wallet`;

CREATE TABLE IF NOT EXISTS `wallet_auto_pay_agreement` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `channel` VARCHAR(32) NOT NULL COMMENT '免密渠道：ALIPAY / WECHAT',
    `channel_user_id` VARCHAR(128) NULL COMMENT '渠道用户标识，如支付宝 user_id / 微信 openid',
    `agreement_no` VARCHAR(128) NULL COMMENT '渠道侧免密协议号',
    `agreement_status` VARCHAR(32) NOT NULL COMMENT 'SIGNING / ACTIVE / CLOSED / FAILED',
    `is_default` INT NOT NULL DEFAULT 0 COMMENT '是否默认免密支付方式：0否 1是',
    `sign_scene` VARCHAR(32) NULL COMMENT '签约场景：APP / H5 / MINI_PROGRAM 等',
    `signed_at` DATETIME NULL COMMENT '签约成功时间',
    `closed_at` DATETIME NULL COMMENT '关闭时间',
    `last_used_at` DATETIME NULL COMMENT '最近一次使用时间',
    `fail_reason` VARCHAR(512) NULL COMMENT '签约或扣款关联失败原因摘要',
    `raw_request` JSON NULL COMMENT '最近一次签约请求快照',
    `raw_response` JSON NULL COMMENT '最近一次渠道返回快照',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '逻辑删除，0未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auto_pay_passenger_channel` (`passenger_id`, `channel`, `is_deleted`),
    KEY `idx_auto_pay_passenger_status` (`passenger_id`, `agreement_status`),
    KEY `idx_auto_pay_agreement_no` (`agreement_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客免密支付授权协议';

CREATE TABLE IF NOT EXISTS `wallet_payment_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `payment_no` VARCHAR(64) NOT NULL COMMENT '支付单号',
    `order_no` VARCHAR(64) NOT NULL COMMENT '业务订单号，对应 order.trip_order.order_no',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID',
    `channel` VARCHAR(32) NOT NULL COMMENT '支付渠道：ALIPAY / WECHAT',
    `agreement_id` BIGINT NULL COMMENT '免密协议ID，wallet_auto_pay_agreement.id',
    `amount` DECIMAL(10, 2) NOT NULL COMMENT '本次支付金额',
    `status` VARCHAR(32) NOT NULL COMMENT 'CREATED / PAYING / SUCCESS / FAILED / CLOSED',
    `channel_trade_no` VARCHAR(128) NULL COMMENT '渠道侧交易号',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '支付幂等键',
    `paid_at` DATETIME NULL COMMENT '支付成功时间',
    `failed_reason` VARCHAR(512) NULL COMMENT '失败原因摘要',
    `notify_payload` JSON NULL COMMENT '最近一次支付回调原始报文快照',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    UNIQUE KEY `uk_payment_idempotency` (`idempotency_key`),
    KEY `idx_payment_order_no` (`order_no`),
    KEY `idx_payment_passenger` (`passenger_id`, `created_at`),
    KEY `idx_payment_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='钱包支付单';
```

### 7.2 calculate 库优惠券表

以下为钱包二期早期草案，已不能完整覆盖车队营销优惠券。正式开发车队营销优惠券时，以《车队营销优惠券_SQL.md》为准。

```sql
USE `calculate`;

CREATE TABLE IF NOT EXISTS `coupon_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name` VARCHAR(128) NOT NULL COMMENT '优惠券名称，如满30减5',
    `coupon_type` VARCHAR(32) NOT NULL COMMENT '券类型：AMOUNT_OFF / DISCOUNT 等，本期优先 AMOUNT_OFF',
    `threshold_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '使用门槛金额',
    `discount_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '固定抵扣金额',
    `discount_rate` DECIMAL(5, 4) NULL COMMENT '折扣率，折扣券预留',
    `max_discount_amount` DECIMAL(10, 2) NULL COMMENT '最大抵扣金额，折扣券预留',
    `city_code` VARCHAR(32) NULL COMMENT '适用城市，空表示不限',
    `product_code` VARCHAR(64) NULL COMMENT '适用产品线，空表示不限',
    `valid_days` INT NULL COMMENT '领取后有效天数',
    `valid_start_at` DATETIME NULL COMMENT '固定有效期开始',
    `valid_end_at` DATETIME NULL COMMENT '固定有效期结束',
    `total_count` INT NULL COMMENT '发放总量，空表示不限',
    `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_coupon_template_status` (`status`, `is_deleted`),
    KEY `idx_coupon_template_scope` (`city_code`, `product_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券模板';

CREATE TABLE IF NOT EXISTS `user_coupon` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id` BIGINT NOT NULL COMMENT '优惠券模板ID',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `coupon_name` VARCHAR(128) NOT NULL COMMENT '券名称快照',
    `threshold_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '门槛金额快照',
    `discount_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '抵扣金额快照',
    `city_code` VARCHAR(32) NULL COMMENT '适用城市快照',
    `product_code` VARCHAR(64) NULL COMMENT '适用产品线快照',
    `status` VARCHAR(32) NOT NULL COMMENT 'UNUSED / LOCKED / USED / EXPIRED / INVALID',
    `locked_order_no` VARCHAR(64) NULL COMMENT '当前锁定订单号',
    `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    `valid_start_at` DATETIME NOT NULL COMMENT '有效期开始',
    `valid_end_at` DATETIME NOT NULL COMMENT '有效期结束',
    `used_at` DATETIME NULL COMMENT '核销时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_coupon_passenger_status` (`passenger_id`, `status`, `valid_end_at`),
    KEY `idx_user_coupon_order` (`locked_order_no`),
    KEY `idx_user_coupon_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户优惠券';

CREATE TABLE IF NOT EXISTS `coupon_use_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_coupon_id` BIGINT NOT NULL COMMENT '用户券ID',
    `template_id` BIGINT NOT NULL COMMENT '优惠券模板ID',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
    `action_type` VARCHAR(32) NOT NULL COMMENT 'LOCK / USE / RELEASE / EXPIRE / INVALID',
    `discount_amount` DECIMAL(10, 2) NULL COMMENT '本次抵扣金额',
    `before_status` VARCHAR(32) NULL COMMENT '变更前状态',
    `after_status` VARCHAR(32) NULL COMMENT '变更后状态',
    `reason` VARCHAR(255) NULL COMMENT '原因说明',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_coupon_record_coupon` (`user_coupon_id`, `created_at`),
    KEY `idx_coupon_record_order` (`order_no`),
    KEY `idx_coupon_record_passenger` (`passenger_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券使用流水';
```

### 7.3 order 库订单结算表

以下为钱包二期早期草案，正式开发时需要合并《车队营销优惠券_SQL.md》中 `platform_service_fee_*`、`carrier_income_amount`、优惠券快照等字段。

```sql
USE `order`;

CREATE TABLE IF NOT EXISTS `trip_order_settlement` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单号，对应 trip_order.order_no',
    `passenger_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',

    `estimated_amount` DECIMAL(10, 2) NULL COMMENT '下单时预估价快照',
    `final_amount` DECIMAL(10, 2) NULL COMMENT '完单后最终车费，优惠前金额快照',

    `coupon_id` BIGINT NULL COMMENT '本单使用的用户券ID，calculate.user_coupon.id',
    `coupon_discount_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '优惠券抵扣金额',
    `payable_amount` DECIMAL(10, 2) NULL COMMENT '应付金额：final_amount - coupon_discount_amount',

    `payment_no` VARCHAR(64) NULL COMMENT '支付单号，wallet.wallet_payment_order.payment_no',
    `payment_status` INT NOT NULL DEFAULT 0 COMMENT '支付状态：0未支付 1支付中 2已支付 3支付失败 4已关闭 5已退款',
    `paid_amount` DECIMAL(10, 2) NULL COMMENT '支付渠道实际成功扣款金额',
    `paid_at` DATETIME NULL COMMENT '支付成功时间',

    `settlement_status` VARCHAR(32) NOT NULL DEFAULT 'INIT' COMMENT '结算状态：INIT / CALCULATED / PAYING / PAID / FAILED / CLOSED',
    `settled_at` DATETIME NULL COMMENT '结算完成时间',

    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_settlement_order_no` (`order_no`),
    KEY `idx_settlement_passenger` (`passenger_id`, `created_at`),
    KEY `idx_settlement_payment_no` (`payment_no`),
    KEY `idx_settlement_payment_status` (`payment_status`, `paid_at`),
    KEY `idx_settlement_coupon_id` (`coupon_id`),
    KEY `idx_settlement_status` (`settlement_status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单结算快照表';
```
