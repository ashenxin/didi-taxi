# 乘客端「福利签到」异常补偿 TECH

> 记录日期：2026-07-19
> 范围：Redis Bitmap 自动收敛、MySQL 签到与积分账本对账、异常留痕、XXL-Job 调度。
> 状态：已实施；代码、SQL 与测试已同步。

---

## 1. 背景与根因

福利签到同时维护四份相关状态：

- `benefit_sign_record`：签到事实；
- `benefit_points_flow`：积分变更明细；
- `benefit_points_account`：积分余额与累计值；
- Redis Bitmap：按用户、月份保存签到日期的派生索引。

当前实现以 MySQL 为权威。签到记录、积分流水和积分账户在同一个 MySQL 事务内提交；事务提交后，再通过 Spring `afterCommit` 回调执行 Redis `SETBIT`。

该设计能避免 MySQL 回滚后留下错误 Bitmap，但 `afterCommit` 不是持久化任务，存在以下失败窗口：

1. MySQL 已提交，Redis 暂时不可用；
2. MySQL 提交后、回调执行前应用进程退出；
3. Redis key 被误删、迁移丢失或数据过期；
4. Redis 写入失败后当前只记录日志，没有可重试状态；
5. 用户当天再次签到只能机会式补写当天 bit，无法重建整月，也无法覆盖不再活跃的用户。

MySQL 三表虽然由事务保护，但系统使用了“业务事实 + 明细流水 + 汇总账户”的冗余模型，目前没有持续验证以下不变量：

- 签到记录是否存在唯一且匹配的积分流水；
- 签到奖励与流水增量是否一致；
- 账户余额是否等于积分流水累计结果；
- 注销账户是否仍保留正余额；
- 最近签到和最近流水指针是否正确。

因此，本问题的根因不是一次普通事务失败，而是缺少跨存储和冗余账本的持续收敛机制。

### 1.1 当前实现口径澄清

Redis Bitmap 当前只执行提交后的 `SETBIT`，不参与签到裁决，也不负责前置防重。重复签到由 MySQL 查询和唯一索引 `uk_sign_customer_date(customer_id, sign_date)` 裁决。

本专题以当前代码为准：

```text
MySQL 事务提交成功
  -> afterCommit 尽力写 Redis Bitmap
  -> MySQL 始终是签到与积分权威
```

---

## 2. 目标与非目标

### 2.1 目标

1. 根据 MySQL 签到记录自动补齐 Redis Bitmap。
2. 定期检测签到记录、积分流水和积分账户之间的不一致。
3. 将每个异常持久化，支持首次发现、重复出现、恢复正常和人工跟踪。
4. 通过 XXL-Job 自动执行，也允许在 XXL-Job 控制台传参后手动执行限定范围。
5. 所有扫描和修复可重复执行，不重复生成无意义记录。
6. 分页、限批执行，避免长事务和一次性加载全量数据。

### 2.2 非目标

- 不自动增加、扣减、补发或清零积分。
- 不自动修改签到记录、积分流水和积分账户。
- 不新增乘客端、后台端或内部 HTTP 触发接口。
- 不改变现有福利概览、积分查询和签到 API。
- 不实现积分兑换、积分过期或复杂任务中心。
- 不把 Redis 提升为权威数据源。

---

## 3. 方案选择

采用“XXL-Job 分页对账 + Redis 自动收敛 + MySQL 异常只留痕”的方案。

未采用的方案：

- 只在用户请求时修复：无法覆盖不活跃用户，也不能形成全量对账结果。
- 为每次 Redis 失败建立完整补偿任务平台：可靠性更强，但超出当前 MVP 所需复杂度，且不能替代历史数据对账。

现有“重复签到时顺便补写当天 Bitmap”的逻辑继续保留，作为实时路径的辅助自愈，不替代定时任务。

---

## 4. 权威数据与校验原则

### 4.1 权威顺序

```text
签到事实：benefit_sign_record
积分变化：benefit_points_flow
展示余额：benefit_points_account（必须能被流水解释）
签到 Bitmap：Redis 派生索引
```

对账任务不得根据 Redis 反向创建 MySQL 签到记录，也不得根据账户余额凭空生成积分流水。

### 4.2 MySQL 只检测不修复

检测到 MySQL 不一致时：

1. 写入或更新异常记录；
2. 保存期望值、实际值及关联业务主键；
3. 输出结构化日志和任务统计；
4. 保持原始业务数据不变；
5. 后续由运营和研发确认处理方式。

即使“正确值”看起来明确，也不得由本任务自动修改积分。

---

## 5. 任务设计

### 5.1 XXL-Job Handler

在 `calculate` 服务新增：

```text
handler: benefitSignReconciliation
```

默认按 `Asia/Shanghai` 每天 02:30 执行一次。任务参数使用 JSON；未传参数时执行日常模式，并回看最近 48 小时发生签到或账户更新的用户。

```json
{
  "mode": "DAILY",
  "customerId": null,
  "yearMonth": null,
  "lookbackHours": 48,
  "pageSize": 200
}
```

参数约定：

| 参数 | 可选值 | 说明 |
|---|---|---|
| `mode` | `DAILY` / `MONTH` / `CUSTOMER` / `FULL_AUDIT` | 扫描模式 |
| `customerId` | 正整数 | `CUSTOMER` 模式必填 |
| `yearMonth` | `yyyyMM` | `MONTH` 可指定；`CUSTOMER` 可选 |
| `lookbackHours` | 24～168 | `DAILY` 回看窗口，默认 48 小时 |
| `pageSize` | 50～1000 | 默认 200，超出范围拒绝执行 |

模式说明：

- `DAILY`：扫描当前月签到用户，并检查 `lookbackHours` 内发生签到或账户更新的用户；用于定时调度。
- `MONTH`：扫描指定月份全部签到用户；用于历史 Bitmap 重建和月度核对。
- `CUSTOMER`：只检查指定乘客；可选指定月份，供 XXL-Job 控制台排障。
- `FULL_AUDIT`：分页检查全部积分账户；只允许人工在低峰期触发，不配置高频定时执行。

不提供 HTTP 触发接口。

### 5.2 执行阶段

一次任务按以下顺序执行：

1. 校验任务参数并生成仅用于日志串联的 `runId`。
2. 按范围分页查找目标乘客或签到月份。
3. 对每个乘客执行 Redis Bitmap 对账。
4. 对每个乘客执行 MySQL 三表一致性检测。
5. 对本次发现的异常执行幂等 upsert。
6. 对已成功完成检测且不再出现的历史异常标记 `RESOLVED`。
7. 汇总扫描数、修复数、异常数、失败数和耗时。
8. 向 XXL-Job 日志输出执行摘要。

单个乘客处理失败时记录失败并继续下一位；数据库整体不可用或参数非法时，任务整体失败。

---

## 6. Redis Bitmap 补偿

### 6.1 Key 与数据来源

```text
key = benefit:sign:bitmap:{customerId}:{yyyyMM}
offset = dayOfMonth - 1
expected bit = MySQL benefit_sign_record 是否存在对应日期
```

只处理业务允许的 1～28 日。

### 6.2 当前月的并发安全

当前月仍可能发生新签到。若任务直接删除并重建整个 key，可能出现以下竞争：

```text
任务读取 MySQL 快照
-> 用户完成新签到并 SETBIT
-> 任务用旧快照覆盖整个 Bitmap
-> 新签到 bit 被错误清除
```

因此当前月采用单向补齐：

- MySQL 有签到、Redis bit=0：自动 `SETBIT 1`；
- MySQL 有签到、Redis bit=1：不处理；
- MySQL 无签到、Redis bit=1：记录 `BITMAP_EXTRA_BIT`，不自动清除。

额外 bit 不参与当前业务裁决，不会导致重复发积分；保守保留比并发误删真实签到更安全。

### 6.3 已关闭月份的精确重建

对于已经结束、不会再产生签到写入的月份，可以按 MySQL 精确重建：

1. 从 MySQL 读取该用户该月全部签到日期；
2. 创建包含 `runId` 的临时 Bitmap key；即使没有签到记录，也初始化一个全 0 Bitmap；
3. 临时 key 设置 1 小时保护性 TTL，避免任务中断留下垃圾 key；
4. 使用 Redis 原子重命名替换正式 key；
5. 对正式 key 执行 `PERSIST`，延续当前 Bitmap 不自动过期的口径；`PERSIST` 失败必须记为 `BITMAP_REPAIR_FAILED`，防止带一小时 TTL 的正式 key 被误报为成功；
6. 复核 1～28 位与 MySQL 一致；
7. 删除残留临时 key。

当月 29～31 日已关闭签到后，也可视为关闭月份执行精确重建。

### 6.4 Redis 失败处理

Redis 不可用时：

- 不修改 MySQL 业务数据；
- 记录 `BITMAP_REPAIR_FAILED`；
- XXL-Job 摘要中的失败数增加；
- 下次定时任务继续尝试；
- 同一异常通过唯一问题键更新，不重复插入无限记录。

---

## 7. MySQL 对账规则

### 7.1 签到记录与积分流水

| 异常类型 | 判断条件 | 级别 |
|---|---|---|
| `SIGN_FLOW_MISSING` | 签到记录没有对应积分流水，或 `points_flow_id` 指向不存在记录 | HIGH |
| `SIGN_FLOW_DUPLICATED` | 同一签到记录对应多条签到奖励流水 | HIGH |
| `SIGN_FLOW_LINK_MISMATCH` | 流水的 `sign_record_id/customer_id/biz_id` 与签到记录不匹配 | HIGH |
| `SIGN_REWARD_MISMATCH` | `sign_record.reward_points != flow.points_delta` | HIGH |
| `SIGN_RULE_MISMATCH` | 签到规则码与流水 `biz_type` 不一致 | MEDIUM |

检测只比较当次落库快照，不使用当前 YAML 奖励配置重算历史奖励。

### 7.2 积分流水与账户

| 异常类型 | 判断条件 | 级别 |
|---|---|---|
| `ACCOUNT_MISSING` | 存在签到或积分流水，但积分账户不存在 | HIGH |
| `ACCOUNT_BALANCE_MISMATCH` | 账户 `available_points` 与全部流水 `points_delta` 合计不一致 | HIGH |
| `ACCOUNT_EARNED_MISMATCH` | `total_earned_points` 与有效入账流水合计不一致 | HIGH |
| `ACCOUNT_CLEARED_MISMATCH` | `total_cleared_points` 与注销清零流水绝对值合计不一致 | HIGH |
| `ACCOUNT_LAST_FLOW_MISMATCH` | `last_points_flow_id` 不是该账户最后一条有效流水 | MEDIUM |
| `ACCOUNT_LAST_SIGN_MISMATCH` | `last_sign_date` 与最后签到日期不一致 | MEDIUM |
| `CANCELLED_ACCOUNT_HAS_BALANCE` | `status=CANCELLED` 但可用积分不为 0 | HIGH |
| `FLOW_BALANCE_CHAIN_BROKEN` | 按流水 ID 排序后，前一条 `balance_after` 与后一条 `balance_before` 不相等 | HIGH |

若历史数据存在明确的期初余额或后续引入积分过期、兑换，必须先扩展流水口径，再启用余额合计规则，避免误报。

### 7.3 异常恢复判定

同一乘客再次被完整、成功扫描时：

- 仍存在的问题更新 `last_detected_at` 和出现次数；
- 已不存在的问题标记 `RESOLVED`；
- 本次扫描中途失败时，不得把旧问题误标为已恢复。

---

## 8. 对账数据模型

只新增一张运维表 `benefit_reconciliation_issue`，放在 `calculate` 库。每次任务的执行状态、参数和统计直接使用 XXL-Job 日志，不重复建设批次表。

### 8.1 `benefit_reconciliation_issue`

记录可追踪的问题：

| 字段 | 说明 |
|---|---|
| `issue_key` | 问题唯一键；由异常类型、乘客、日期/业务主键组成 |
| `issue_type` | §7 定义的异常类型或 Bitmap 异常 |
| `severity` | `HIGH/MEDIUM/LOW` |
| `customer_id` | 乘客 ID |
| `sign_date/year_month` | 可选的签到范围 |
| `reference_type` | `SIGN_RECORD/POINTS_FLOW/POINTS_ACCOUNT/BITMAP` |
| `reference_id` | 签到记录、流水或账户主键 |
| `expected_snapshot` | JSON，权威或推导出的期望值 |
| `actual_snapshot` | JSON，检测到的实际值 |
| `status` | `OPEN/RESOLVED` |
| `first_detected_at` | 首次发现时间 |
| `last_detected_at` | 最近发现时间 |
| `resolved_at` | 恢复时间 |
| `occurrence_count` | 重复检测次数 |
| `last_run_id` | 最近一次扫描生成的日志串联号；不关联独立批次表 |

`issue_key` 建唯一索引，保证任务重跑不会重复制造相同问题。

异常快照不得保存手机号、token 等敏感信息。

---

## 9. 分页、事务与幂等

- 使用稳定主键游标分页，不使用大 offset 深分页。
- 每个乘客独立完成检测和异常 upsert，不开启覆盖整个批次的长事务。
- Redis `SETBIT 1` 天然可重复执行。
- 历史月份临时 key 必须包含 `runId`，防止并发任务互相覆盖。
- 只配置一个 `benefitSignReconciliation` XXL-Job 任务，阻塞策略固定为 `SERIAL_EXECUTION`；定时和人工传参都通过该任务触发，避免同一 Handler 并行运行。
- 本期不再增加 Redis 分布式任务锁；如果将来拆成多个独立 Job，再重新设计跨 Job 互斥。
- 异常记录写入失败时不得继续声称该用户对账成功。
- 日常任务应限制单批处理量，并在下一次调度继续处理剩余数据。

---

## 10. 可观测性与人工处理

### 10.1 XXL-Job 控制台配置

`calculate` 在 `local` profile 中通过 `application-local.yml` 启用执行器，控制台配置口径如下：

| 配置项 | 值 |
|---|---|
| 执行器 AppName | `calculate-executor` |
| 注册方式 | 自动注册；本地地址默认 `http://127.0.0.1:9996/` |
| 任务描述 | 福利签到异常对账补偿 |
| Cron | `0 30 2 * * ?` |
| 运行模式 | `BEAN` |
| JobHandler | `benefitSignReconciliation` |
| 路由策略 | `FIRST` |
| 调度过期策略 | `DO_NOTHING` |
| 阻塞处理策略 | `SERIAL_EXECUTION` |
| 任务超时时间 | `1800` 秒 |
| 失败重试次数 | `0` |
| 默认任务参数 | `{"mode":"DAILY","pageSize":500}` |

控制台任务已按上述口径完成配置。本期不配置 XXL-Job 自动失败重试；执行失败时查看日志并由运营/研发人工处理。

### 10.2 日志与告警

XXL-Job 日志至少输出：

```text
runId/mode/scannedCustomerCount/bitmapRepairedCount/
issueFoundCount/repairFailedCount/durationMs/status
```

逐条业务日志使用 `customerId + issueType + referenceId`，不得只输出无法聚合的异常堆栈。

建议告警口径：

- XXL-Job 执行失败：立即告警；
- 存在单用户失败或 Redis 连续失败：告警；
- 新增 HIGH 问题：告警并进入人工排查；
- 同一 HIGH 问题连续多次未恢复：升级告警。

本期不开发问题处置后台页面。运营或研发通过数据库、日志和 XXL-Job 结果审阅异常；后续若问题量上升，再在 admin-api 增加只读查询与受控处理流程。

---

## 11. 测试与验收

### 11.1 Redis 补偿

1. MySQL 有签到、当前月 bit 缺失：任务补写为 1。
2. 当前月存在额外 bit：记录异常但不清除。
3. 历史月 Bitmap 缺失或错误：按 MySQL 精确重建。
4. Redis 不可用：MySQL 不变，问题和 XXL-Job 失败摘要被记录，下次可重试。
5. 任务重复执行：Bitmap 和异常记录不重复膨胀。
6. 当前月补偿与新签到并发：不得清除新签到 bit。

### 11.2 MySQL 对账

1. 正常签到三表数据不产生异常。
2. 签到缺流水、错流水、重复流水分别命中正确异常类型。
3. 账户余额、累计获得、累计清零不一致时只记录，不修改任何积分字段。
4. 注销账户仍有余额时记录 HIGH 问题。
5. 修复测试数据后再次扫描，原问题转为 `RESOLVED`。
6. 单个用户扫描失败时，其他用户继续处理；失败用户旧问题不被误标为恢复。

### 11.3 任务控制

1. 非法 `mode/yearMonth/pageSize` 直接失败且不扫描。
2. `CUSTOMER` 只扫描指定用户。
3. 游标分页无遗漏、无重复。
4. 同范围并发任务只有一个获得执行权。
5. XXL-Job 日志统计与实际处理数量一致。

建议回归命令：

```bash
mvn -pl calculate test
mvn -pl passenger-api test
```

---

## 12. 换号、销号边界

- 更换手机号只更新原 `customer.id` 对应记录，福利数据按 `customerId` 归属，因此无需迁移签到记录或积分账户。
- 注销成功后，`passenger-api` 会尽力调用 calculate 清零积分并将福利账户置为 `CANCELLED`。
- 如果账号已经逻辑注销，但上述跨服务调用根本没有到达 calculate，本服务中的账户仍可能保持 `ACTIVE`。calculate 仅凭本地三张表无法证明 passenger 账号已经注销，因此本任务不能识别这种“注销通知完全丢失”的情况。
- 本期不为该边界新增跨服务查询或自动改积分能力；调用失败继续保留错误日志并由运营联系研发处理。后续如需自动发现，应单独设计 passenger 账号状态对账，不能根据手机号推断。

已实施内容包括单一问题表、MySQL 只读检测、Redis 当前月补齐与历史月重建、异常幂等更新与恢复、XXL-Job Handler 与参数校验；XXL-Job 控制台任务也已完成配置。
