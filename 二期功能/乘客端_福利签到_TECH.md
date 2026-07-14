# 乘客端「福利签到」TECH

> 记录日期：2026-07-14
> 范围：Redis Bitmap、后端计算、MySQL 事务、失败补偿、注销/换号联动。
> 状态：技术方案草案，待接口和 SQL 评审后落地。

---

## 1. 总体设计

本期采用：

```text
Redis Bitmap + MySQL 账本 + 简单 YAML 配置
```

职责划分：

```text
Redis Bitmap：记录用户某天是否签到，负责快速判断和幂等前置。
后端服务：读取配置，计算连续天数和奖励积分。
MySQL：记录签到事实、积分账户、积分流水，作为最终权威。
```

第一期不做完整规则引擎。规则引擎成本过大，后续任务中心、积分商城、用户分层运营明确后再评估。

---

## 2. Redis Bitmap

### 2.1 Key 设计

按用户 + 自然月维护 Bitmap：

```text
benefit:sign:bitmap:{customerId}:{yyyyMM}
```

示例：

```text
benefit:sign:bitmap:10001:202607
```

offset：

```text
offset = dayOfMonth - 1
```

示例：

```text
2026-07-01 -> offset 0
2026-07-14 -> offset 13
```

虽然页面只展示 28 天，Redis 仍按自然月日期记录，29-31 号不写签到记录、不发积分。

### 2.2 Lua 幂等

Redis Lua 只做“今天是否首次签到”：

```text
GETBIT key offset
如果已为 1，返回已签到
否则 SETBIT key offset 1，返回新签到
```

Redis 只是前置防重，最终权威仍是 MySQL。

---

## 3. 配置计算

第一期使用 YAML 配置：

```yaml
benefit:
  sign-in:
    enabled: true
    timezone: Asia/Shanghai
    cycle: MONTHLY
    display-days: 28
    sign-days:
      start-day-of-month: 1
      end-day-of-month: 28
    rewards:
      default:
        points: 5
      continuous:
        - every-days: 7
          points: 35
          include-default: false
```

校验规则：

- 配置文件缺失或非法时，签到失败。
- 前端提示“签到配置异常，请稍后再试”。
- 不写 Redis。
- 不写 MySQL。
- 服务端输出错误日志，由乘客侧报错推动运营/研发发现 P0 配置问题。

配置变更：

- 只影响新签到。
- 历史签到奖励不重算。
- `benefit_sign_record.reward_snapshot` 和 `benefit_points_flow.rule_snapshot` 可记录当次规则快照。

---

## 4. 签到流程

```text
1. 校验登录态、账号状态、日期范围。
2. 读取并校验签到配置文件。
3. Redis Lua 判断并写入当日 Bitmap。
4. 开启 MySQL 事务。
5. 获取或创建 benefit_points_account，并锁定账户行。
6. 再次确认账户 status = ACTIVE。
7. 计算 continuous_days、reward_points、reward_rule_code。
8. 插入 benefit_sign_record。
9. 插入 benefit_points_flow。
10. 更新 benefit_sign_record.points_flow_id。
11. 更新 benefit_points_account 余额与 last_sign_date。
12. 提交事务。
```

说明：

- 三张 MySQL 表写操作必须在同一个事务中完成。
- 积分账户懒创建：查询积分时账户不存在返回 0；首次签到成功时在事务内创建账户并入账。
- MySQL 唯一索引 `uk_sign_customer_date(customer_id, sign_date)` 是最终防重复签到兜底。
- 今日已签到时返回业务成功态，文案为「今日已签到」，不重复加积分。

---

## 5. 并发控制

### 5.1 重复点击

使用两层防线：

```text
Redis Lua
MySQL unique(customer_id, sign_date)
```

### 5.2 签到和注销并发

积分账户更新使用行锁或乐观锁。

规则：

- 签到事务内锁定 `benefit_points_account` 后，必须再次检查 `status = ACTIVE`。
- 注销清零也锁定同一账户行。
- 注销先完成，则签到失败。
- 签到先完成，则注销清零包含本次签到后的余额。

---

## 6. 失败补偿

### 6.1 Redis 成功但 MySQL 失败

第一期处理：

- 代码输出一行异常日志。
- 日志包含 `customerId/signDate/requestId`。
- 定时补偿任务先记录为 TODO，不在第一期实现。

### 6.2 MySQL 成功但 Redis 丢失

第一期先不处理。

原因：

- 概率较低。
- MySQL 是权威。
- 后续可通过 MySQL 签到记录重建当月 Bitmap。

---

## 7. 积分流水 Biz ID

签到流水：

```text
biz_type = SIGN_IN_DAILY / SIGN_IN_CONTINUOUS_7
biz_id = benefit_sign_record.id
```

注销清零流水：

```text
biz_type = ACCOUNT_CANCEL_CLEAR
biz_id = account_cancel:{customerId}:{cancelRequestId}
```

第 7 天 35 积分第一期只写一条 `SIGN_IN_CONTINUOUS_7 +35` 流水，不拆成 `+5/+30`。

---

## 8. 表设计与数据治理

表放在 `calculate` 库：

- `benefit_sign_record`
- `benefit_points_account`
- `benefit_points_flow`

第一期三张表均按普通单表设计：

- 不做分区。
- 不做分库分表。
- 后续按真实数据量再评估归档或拆分。

### 8.1 表膨胀风险

`benefit_sign_record` 和 `benefit_points_flow` 都会随签到行为线性增长：

```text
每天 N 个乘客签到：
benefit_sign_record 每天新增 N 行。
benefit_points_flow 每天至少新增 N 行。
```

因此这两张表存在长期膨胀风险。第一期先不引入分区、分库分表或归档任务，原因是：

- 当前 MVP 查询模式简单，主要按 `customer_id` 和当月数据查询。
- 过早做分区/分库分表会增加索引、唯一约束、迁移和运维复杂度。
- 真实签到规模尚未验证，先用普通单表降低实现成本。

后续如果数据量明显增长，再按优先级评估：

1. 历史数据归档。
2. `benefit_sign_record` 按月份或年份做归档/拆分。
3. `benefit_points_flow` 按时间归档，保留近 12-24 个月在线查询。
4. 最后才评估分区或分库分表。

完整 DDL 见《乘客端_福利签到_SQL.md》。

---

## 9. 服务调用边界

建议：

```text
乘客 APP -> passenger-api -> calculate
```

- `passenger-api` 对 APP 暴露 `/app/api/v1/benefits/**`。
- `calculate` 负责签到、积分账户、积分流水的核心逻辑。
- `passenger-api` 从 JWT 获取 `customerId`，不允许前端传用户 ID。
