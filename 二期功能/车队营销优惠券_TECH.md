# 车队营销优惠券 TECH

> 本文档描述车队营销优惠券一期技术方案。
> 产品口径见《车队营销优惠券_PRD.md》，接口契约见《车队营销优惠券_API.md》，SQL 草案见《车队营销优惠券_SQL.md》。

## 1. 服务边界

| 服务 | 职责 |
|---|---|
| `admin-api` | 后台优惠券管理 BFF；复用计价规则数据域，超管写、非超管只读。 |
| `passenger-api` | 乘客端领券、查询可用券、订单结算前选择券的 BFF。 |
| `calculate` | 优惠券模板、用户券、优惠计算、锁定、释放、核销、用券流水。 |
| `order` | 订单最终承运 `company_id`、订单结算快照、支付状态和服务费快照。 |
| `wallet` | 免密协议和支付单；不负责优惠券规则。 |
| `capacity` | 司机归属 `company_id`，派单后写入最终承运车队。 |

明确边界：

- 优惠券相关表放在 `calculate` 库。
- 订单结算金额快照放在 `order` 库。
- 免密支付协议和支付单放在 `wallet` 库。
- `fare_rule` 不新增字段。
- 不新增 `fleet_id`。

## 2. 数据模型

### 2.1 coupon_template

优惠券模板，表达车队发布的营销规则。

关键字段：

```text
company_id
company_no
company_name_snapshot
team_id_snapshot
team_name_snapshot
name
coupon_type
threshold_amount
discount_amount
discount_rate
max_discount_amount
city_code
product_code
valid_start_at
valid_end_at
total_count
received_count
used_count
per_user_limit
issue_type
source_type
activity_code
rule_config
status
```

状态：

```text
DRAFT
PUBLISHED
OFFLINE
```

券类型：

```text
AMOUNT_OFF
PERCENT_OFF
SPECIAL
```

### 2.2 user_coupon

用户券，表示某个乘客领取后的券实例。

必须保存模板关键字段快照：

```text
template_id
passenger_id
company_id
coupon_name
coupon_type
threshold_amount
discount_amount
discount_rate
max_discount_amount
city_code
product_code
status
locked_order_no
received_at
valid_start_at
valid_end_at
used_at
rule_snapshot
```

状态：

```text
UNUSED
LOCKED
USED
EXPIRED
```

### 2.3 coupon_use_record

用券动作流水，不是领取流水。

必须记录金额交易相关动作：

```text
LOCK
RELEASE
USE
REFUND_RESTORE
EXPIRE
```

### 2.4 trip_order_settlement

订单结算快照表，位于 `order` 库。

需要保存：

```text
coupon_id
coupon_template_id
coupon_company_id
coupon_type
coupon_rule_snapshot
coupon_discount_amount
payable_amount
platform_service_fee_rate
platform_service_fee_amount
carrier_income_amount
settlement_snapshot
```

## 3. 后台管理方案

### 3.1 入口

后台从计价规则详情页进入优惠券方案页。

查询维度：

```text
fare_rule.company_id
fare_rule.city_code
fare_rule.product_code
```

用上述维度查询 `coupon_template`，不在 `fare_rule` 增加字段，也不保存 `fare_rule_id`。

### 3.2 权限

- 可见范围复用计价规则可见范围。
- 超管可新增、编辑、发布、下架。
- 非超管只读。
- 系统不记录运营与车队线下确认过程。

### 3.3 状态约束

- `DRAFT` 可编辑。
- `PUBLISHED` 可被领取和使用，原则上不建议直接改核心金额规则。
- `OFFLINE` 不再进入领取弹窗；已领取的用户券是否可用按用户券快照和状态判断，若业务要求下架后禁止继续用，需要另补禁用策略。

一期建议：

```text
模板 OFFLINE 后不再发新券。
已领取用户券是否继续可用，默认按 user_coupon 快照和状态判断。
```

## 4. 乘客领券流程

### 4.1 登录后查询可领取券

查询条件：

- `coupon_template.status = PUBLISHED`
- 当前时间在 `valid_start_at` 与 `valid_end_at` 之间
- `received_count < total_count`
- 当前用户未达到 `per_user_limit`
- 符合数据可见或用户可领取条件

### 4.2 一键领取

用户确认领取全部后：

1. 查询当前仍可领取模板。
2. 对每个模板做并发保护。
3. 插入 `user_coupon`，保存模板快照。
4. 增加 `coupon_template.received_count`。
5. 领取动作本期不写正式领取流水，最多写业务日志。

并发建议：

```sql
UPDATE coupon_template
SET received_count = received_count + 1
WHERE id = ?
  AND status = 'PUBLISHED'
  AND received_count < total_count
  AND is_deleted = 0;
```

随后插入 `user_coupon`。同一模板同一用户需通过唯一约束或事务内检查保证不重复领取。

## 5. 可用券计算

### 5.1 输入上下文

```text
passenger_id
order_no
company_id
city_code
product_code
final_amount
current_time
```

其中 `company_id` 必须来自最终承运订单 `trip_order.company_id`。

### 5.2 可用性规则

用户券必须满足：

- `passenger_id` 匹配当前乘客。
- `status = UNUSED`。
- 当前时间在有效期内。
- `company_id` 匹配最终承运车队。
- `city_code` 匹配订单城市。
- `product_code` 匹配订单产品线。
- `threshold_amount <= final_amount`。

### 5.3 优惠金额计算

固定金额减免：

```text
discount = min(discount_amount, final_amount)
```

比例折扣：

```text
raw_discount = final_amount * (1 - discount_rate)
discount = raw_discount
```

封顶折扣：

```text
raw_discount = final_amount * (1 - discount_rate)
discount = min(raw_discount, max_discount_amount)
```

最终兜底：

```text
discount = max(0, min(discount, final_amount))
payable_amount = final_amount - discount
```

### 5.4 最优券排序

```text
discount desc
valid_end_at asc
user_coupon.id asc
```

用户选择“不使用优惠券”时，本单结算应明确不锁券。

## 6. 锁券、核销、释放

### 6.1 锁券

锁券必须使用状态条件更新：

```sql
UPDATE user_coupon
SET status = 'LOCKED',
    locked_order_no = ?,
    updated_at = NOW()
WHERE id = ?
  AND passenger_id = ?
  AND status = 'UNUSED'
  AND valid_start_at <= NOW()
  AND valid_end_at > NOW();
```

成功后写 `coupon_use_record(action_type=LOCK)`。

### 6.2 核销

支付成功后核销：

```text
LOCKED -> USED
```

校验：

- 用户券属于当前乘客。
- 用户券锁定订单号等于当前订单号。
- 当前状态为 `LOCKED`；重复核销需要幂等返回成功。

写 `coupon_use_record(action_type=USE)`。

### 6.3 释放

支付失败、取消支付等场景释放：

```text
LOCKED -> UNUSED
```

如果释放时已过期，可直接置为 `EXPIRED`，具体按退款/失败场景区分。

写 `coupon_use_record(action_type=RELEASE)`。

### 6.4 全额退款退券

全额退款：

```text
USED -> UNUSED
```

如果退款发生时券已过有效期：

```text
USED -> EXPIRED
```

写 `coupon_use_record(action_type=REFUND_RESTORE)`。

## 7. 订单结算

支付前或完单结算时：

1. 读取最终订单和最终承运 `company_id`。
2. 使用最终金额 `final_amount` 查询可用券。
3. 用户未选择不用券时，系统选择最优券。
4. 锁定优惠券。
5. 写入或更新 `trip_order_settlement`。
6. 创建 `wallet_payment_order` 并扣款。
7. 支付成功后核销券，更新结算支付状态。
8. 支付失败后释放券，更新结算支付状态。

结算公式：

```text
payable_amount = final_amount - coupon_discount_amount
platform_service_fee_amount = payable_amount * 0.05
carrier_income_amount = payable_amount - platform_service_fee_amount
```

金额建议统一按两位小数入库，舍入方式后续在结算统一规范中定版；一期可暂用 BigDecimal HALF_UP。

## 8. 异步派单风险处理

当前风险：

```text
预估阶段候选司机 company_id != 最终承运司机 company_id
```

技术约束：

- 下单预估阶段可展示预计可用券，但不得锁定强车队绑定券。
- 锁券必须在最终 `trip_order.company_id` 确认后进行。
- 支付前金额确认必须重新调用 calculate。
- 结算快照必须保存最终 `company_id`、最终规则快照和优惠快照。
- 不允许用预估阶段 `fare_rule_id` 或候选司机 `company_id` 作为最终支付依据。

## 9. 幂等与并发

- 模板领取数量用条件更新防止超发。
- 同一用户同一模板通过唯一约束防止重复领取。
- 锁券用 `UNUSED -> LOCKED` 状态条件更新。
- 核销、释放、退款恢复按 `order_no + user_coupon_id + action_type` 做幂等。
- 同一订单只允许一条有效结算快照。
- 同一订单最多关联一张用户券。

## 10. 定时与补偿

建议后续补充：

- 扫描过期 `UNUSED` 用户券，置为 `EXPIRED`。
- 扫描长时间 `LOCKED` 且订单未支付的券，按订单状态释放或过期。
- 扫描支付成功但券未核销、支付失败但券未释放的异常订单。
- 输出优惠券发放、领取、使用、优惠总额统计。

## 11. 与现有钱包二期的差距

当前已有钱包二期表和代码只能支持较简单的固定金额券。正式进入车队营销优惠券开发前，需要补齐：

- `coupon_template.company_id` 及车队快照字段。
- `coupon_template.per_user_limit / received_count / used_count`。
- `coupon_template.activity_code / rule_config`。
- `user_coupon.coupon_type / discount_rate / max_discount_amount / rule_snapshot`。
- `coupon_use_record.rule_snapshot`。
- `trip_order_settlement` 服务费、承运侧收入和优惠券快照字段。
- 最优券计算从固定 `discount_amount` 改为按订单动态计算。
- 可用券计算必须使用最终承运 `company_id`。
