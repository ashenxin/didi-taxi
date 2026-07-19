# 乘客端「福利签到」SQL

> 记录日期：2026-07-14；2026-07-19 按当前实现修订签到事务与 Redis 同步顺序。
> 范围：每月 28 天签到、积分账户、积分流水、注销清零、异常对账留痕。
> 状态：已同步至 `calculate/src/main/resources/sql/calculate_schema.sql` 与测试 schema；当前实现以代码和正式 API/TECH 文档为准。

---

## 1. 库表归属

| 库 | 表 | 说明 |
| --- | --- | --- |
| `calculate` | `benefit_sign_record` | 乘客签到记录 |
| `calculate` | `benefit_points_account` | 乘客积分账户 |
| `calculate` | `benefit_points_flow` | 乘客积分流水 |
| `calculate` | `benefit_reconciliation_issue` | 福利签到与积分对账异常；不自动修改积分 |

定版口径：

- Redis Bitmap 只记录签到事实和辅助统计。
- MySQL 是签到记录和积分账本权威。
- 表放在 `calculate` 库，不放入 `wallet`。
- 第一期不做积分兑换、积分过期，只做签到获取、积分查询、注销清零。
- 注销账号时积分余额清零，不物理删除签到记录和流水。
- 更换手机号不影响积分，因为积分跟 `customerId` 走。
- 第一期三张表均按普通单表设计，不做分区、不做分库分表；后续按真实数据量再评估归档或拆分。

---

## 2. calculate.benefit_sign_record

用途：

- 记录每个乘客每天的签到事实。
- 作为 Redis Bitmap 丢失后的重建来源。
- 使用唯一索引兜底防止重复签到。

目标 DDL：

```sql
CREATE TABLE IF NOT EXISTS `benefit_sign_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `sign_date` DATE NOT NULL COMMENT '签到日期，业务时区 Asia/Shanghai',
    `sign_year_month` CHAR(6) NOT NULL COMMENT '签到年月 yyyyMM',
    `day_of_month` TINYINT NOT NULL COMMENT '自然月日期，1-28 可领奖签到',
    `bitmap_offset` TINYINT NOT NULL COMMENT 'Redis Bitmap offset，day_of_month - 1',
    `continuous_days` INT NOT NULL DEFAULT 1 COMMENT '当月连续签到天数，月初重新计算',
    `reward_points` INT NOT NULL DEFAULT 0 COMMENT '本次签到总奖励积分',
    `reward_rule_code` VARCHAR(64) NOT NULL COMMENT '命中奖励规则编码，如 SIGN_IN_DAILY/SIGN_IN_CONTINUOUS_7',
    `reward_snapshot` JSON NULL COMMENT '本次签到奖励规则快照',
    `points_flow_id` BIGINT NULL COMMENT '关联积分流水ID，写入流水后回填',
    `source_type` VARCHAR(32) NOT NULL DEFAULT 'APP' COMMENT '签到来源：APP/ADMIN_REPAIR/SYSTEM_REPAIR',
    `request_id` VARCHAR(64) NULL COMMENT '请求ID，便于排查幂等与链路',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sign_customer_date` (`customer_id`, `sign_date`),
    KEY `idx_sign_customer_month` (`customer_id`, `sign_year_month`, `day_of_month`),
    KEY `idx_sign_month_date` (`sign_year_month`, `sign_date`),
    KEY `idx_sign_points_flow` (`points_flow_id`),
    KEY `idx_sign_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客福利签到记录';
```

字段说明：

- `sign_date` 使用后端业务时区 `Asia/Shanghai` 计算。
- `sign_year_month` 用于快速查当月 28 天签到列表。
- `day_of_month` 第一期只允许 `1-28`。29-31 号不写签到记录。
- `bitmap_offset = day_of_month - 1`，便于和 Redis Bitmap 对齐。
- `continuous_days` 是当月连续签到天数，跨月清零。
- `reward_points` 由后端按配置计算，前端不能传。
- `reward_snapshot` 保存当次配置快照，配置变更后历史奖励不重算。
- `source_type` 预留补偿/后台修复来源，第一期正常签到为 `APP`。

约束说明：

- `uk_sign_customer_date` 是防重复签到的最终兜底。
- MySQL 8.0 可后续补充 `CHECK (day_of_month BETWEEN 1 AND 28)`，如测试环境兼容性不稳定，可先交由服务层校验。

---

## 3. calculate.benefit_points_account

用途：

- 保存乘客当前可用积分。
- 查询积分接口直接读取本表。
- 注销时将 `available_points` 清零并将状态置为 `CANCELLED`。

目标 DDL：

```sql
CREATE TABLE IF NOT EXISTS `benefit_points_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `available_points` INT NOT NULL DEFAULT 0 COMMENT '当前可用积分',
    `total_earned_points` INT NOT NULL DEFAULT 0 COMMENT '历史累计获得积分',
    `total_used_points` INT NOT NULL DEFAULT 0 COMMENT '历史累计消耗积分；第一期不做兑换，默认0',
    `total_cleared_points` INT NOT NULL DEFAULT 0 COMMENT '历史累计清零积分，主要用于注销',
    `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CANCELLED/FROZEN',
    `last_sign_date` DATE NULL COMMENT '最近一次签到日期',
    `last_points_flow_id` BIGINT NULL COMMENT '最近一笔积分流水ID',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_account_customer` (`customer_id`),
    KEY `idx_points_account_status` (`status`, `updated_at`),
    KEY `idx_points_account_last_sign` (`last_sign_date`),
    KEY `idx_points_account_last_flow` (`last_points_flow_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客福利积分账户';
```

字段说明：

- `available_points` 是前端展示的积分总和。
- `total_earned_points` 只累计正向获取积分。
- `total_used_points` 第一期不做兑换，先保留为 0。
- `total_cleared_points` 记录注销等清零动作累计值，便于审计。
- `status = CANCELLED` 后禁止签到和积分入账。
- `version` 用于乐观锁；也可以在事务中 `SELECT ... FOR UPDATE` 锁定账户行。

建议更新规则：

```sql
-- 签到入账时：
available_points = available_points + reward_points
total_earned_points = total_earned_points + reward_points
last_sign_date = 当前签到日期
last_points_flow_id = 本次流水ID
version = version + 1

-- 注销清零时：
total_cleared_points = total_cleared_points + 清零前 available_points
available_points = 0
status = 'CANCELLED'
last_points_flow_id = 清零流水ID
version = version + 1
```

---

## 4. calculate.benefit_points_flow

用途：

- 记录积分所有变更。
- 支撑审计、排查、注销清零。
- 第一阶段包括签到入账和注销清零。

目标 DDL：

```sql
CREATE TABLE IF NOT EXISTS `benefit_points_flow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `account_id` BIGINT NOT NULL COMMENT '积分账户ID',
    `biz_type` VARCHAR(32) NOT NULL COMMENT '业务类型：SIGN_IN_DAILY/SIGN_IN_CONTINUOUS_7/ACCOUNT_CANCEL_CLEAR',
    `biz_id` VARCHAR(64) NOT NULL COMMENT '业务ID：签到记录ID或注销业务号',
    `points_delta` INT NOT NULL COMMENT '积分变化，正数增加，负数扣减/清零',
    `balance_before` INT NOT NULL COMMENT '变更前可用积分',
    `balance_after` INT NOT NULL COMMENT '变更后可用积分',
    `flow_direction` VARCHAR(16) NOT NULL COMMENT '方向：IN/OUT',
    `sign_record_id` BIGINT NULL COMMENT '签到记录ID，签到类流水必填',
    `remark` VARCHAR(255) NULL COMMENT '备注',
    `rule_snapshot` JSON NULL COMMENT '规则快照或清零上下文',
    `request_id` VARCHAR(64) NULL COMMENT '请求ID，便于排查幂等与链路',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_flow_biz` (`customer_id`, `biz_type`, `biz_id`),
    KEY `idx_points_flow_customer_time` (`customer_id`, `created_at`),
    KEY `idx_points_flow_account_time` (`account_id`, `created_at`),
    KEY `idx_points_flow_sign_record` (`sign_record_id`),
    KEY `idx_points_flow_biz_type` (`biz_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乘客福利积分流水';
```

字段说明：

- `biz_type` 第一期枚举：
  - `SIGN_IN_DAILY`：普通签到奖励 5 积分。
  - `SIGN_IN_CONTINUOUS_7`：连续第 7 天奖励 35 积分。
  - `ACCOUNT_CANCEL_CLEAR`：注销账号清零积分。
- `biz_id`：
  - 签到流水使用 `benefit_sign_record.id` 字符串。
  - 注销清零使用 `account_cancel:{customerId}:{cancelRequestId}`。
- `uk_points_flow_biz` 防止同一业务重复入账。
- `points_delta` 注销清零时为负数，等于 `-balance_before`。
- `balance_after` 不允许小于 0，由服务层校验。
- 第 7 天 35 积分第一期只写一条 `SIGN_IN_CONTINUOUS_7 +35` 流水，不拆成 `+5/+30`。

---

## 5. calculate.benefit_reconciliation_issue

用途：

- 持久化 Redis Bitmap、签到记录、积分流水和积分账户之间的异常。
- 相同问题重复发现时更新最近发现时间与次数，不重复生成新记录。
- 源数据恢复一致后将问题标记为 `RESOLVED`。
- 本表只记录问题，不触发积分增加、扣减、补发或清零。
- 任务批次信息使用 XXL-Job 日志，不创建 `benefit_reconciliation_run` 表。

目标 DDL：

```sql
CREATE TABLE IF NOT EXISTS `benefit_reconciliation_issue` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `issue_key` CHAR(64) NOT NULL COMMENT '稳定问题键，SHA-256(异常类型/乘客/范围/业务主键)',
    `issue_type` VARCHAR(64) NOT NULL COMMENT '异常类型',
    `severity` VARCHAR(16) NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
    `customer_id` BIGINT NOT NULL COMMENT '乘客ID，对应 passenger.customer.id',
    `sign_date` DATE NULL COMMENT '关联签到日期',
    `year_month` CHAR(6) NULL COMMENT '关联签到年月 yyyyMM',
    `reference_type` VARCHAR(32) NULL COMMENT '关联对象类型：SIGN_RECORD/POINTS_FLOW/POINTS_ACCOUNT/BITMAP',
    `reference_id` VARCHAR(64) NULL COMMENT '关联对象ID或 Bitmap offset',
    `expected_snapshot` JSON NULL COMMENT '期望值快照，不得包含手机号、token等敏感信息',
    `actual_snapshot` JSON NULL COMMENT '实际值快照，不得包含手机号、token等敏感信息',
    `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED',
    `first_detected_at` DATETIME NOT NULL COMMENT '首次发现时间',
    `last_detected_at` DATETIME NOT NULL COMMENT '最近发现时间',
    `resolved_at` DATETIME NULL COMMENT '恢复时间',
    `occurrence_count` INT NOT NULL DEFAULT 1 COMMENT '重复发现次数',
    `last_run_id` VARCHAR(64) NOT NULL COMMENT '最近一次扫描批次号，仅用于日志串联',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_benefit_reconciliation_issue_key` (`issue_key`),
    KEY `idx_benefit_issue_customer_status` (`customer_id`, `status`, `last_detected_at`),
    KEY `idx_benefit_issue_type_status` (`issue_type`, `status`, `last_detected_at`),
    KEY `idx_benefit_issue_severity_status` (`severity`, `status`, `last_detected_at`),
    KEY `idx_benefit_issue_last_run` (`last_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='福利签到与积分对账异常';
```

`issue_key` 必须包含 `customerId`，避免不同乘客恰好拥有相同业务主键时发生唯一键冲突。`last_run_id` 只是应用生成的日志串联号，不设置外键。

---

## 6. 可选 CHECK 约束

如果确认 MySQL 版本和测试环境都支持 `CHECK`，可加以下约束。否则先由服务层校验。

```sql
ALTER TABLE `benefit_sign_record`
    ADD CONSTRAINT `ck_sign_day_of_month` CHECK (`day_of_month` BETWEEN 1 AND 28),
    ADD CONSTRAINT `ck_sign_bitmap_offset` CHECK (`bitmap_offset` BETWEEN 0 AND 27),
    ADD CONSTRAINT `ck_sign_reward_points` CHECK (`reward_points` >= 0);

ALTER TABLE `benefit_points_account`
    ADD CONSTRAINT `ck_points_account_available` CHECK (`available_points` >= 0),
    ADD CONSTRAINT `ck_points_account_total_earned` CHECK (`total_earned_points` >= 0),
    ADD CONSTRAINT `ck_points_account_total_used` CHECK (`total_used_points` >= 0),
    ADD CONSTRAINT `ck_points_account_total_cleared` CHECK (`total_cleared_points` >= 0);
```

---

## 7. 推荐事务顺序草案

### 7.1 签到入账

```text
1. 校验登录态、账号状态、日期范围。
2. 读取并校验签到配置文件。
3. 开启 MySQL 事务。
4. 获取或创建 benefit_points_account，并锁定账户行。
5. 再次确认账户 status = ACTIVE。
6. 计算 continuous_days、reward_points、reward_rule_code。
7. 插入 benefit_sign_record。
8. 插入 benefit_points_flow。
9. 更新 benefit_sign_record.points_flow_id。
10. 更新 benefit_points_account 余额与 last_sign_date。
11. 提交事务。
12. afterCommit 执行 Redis SETBIT，记录当日 Bitmap。
```

说明：

- 配置文件缺失或非法时，直接返回系统配置错误；不写 MySQL，也不触发提交后的 Redis 写入。
- `benefit_sign_record`、`benefit_points_flow`、`benefit_points_account` 三张表写操作必须在同一个 MySQL 事务中完成。
- 积分账户采用懒创建：查询积分时账户不存在返回 0；首次签到成功时在本事务内创建 `benefit_points_account` 并入账。
- 如果第 7 步唯一索引冲突，说明已签到；接口返回已签到，不重复发放积分。
- Redis Bitmap 是 MySQL 签到事实的派生索引，不参与首次签到裁决。
- MySQL 成功但 Redis 写入失败时记录包含 `customerId/signDate/requestId` 的警告；自动重建和三表对账见《乘客端_福利签到_异常补偿_TECH.md》。
- 异常补偿只自动修复 Bitmap；MySQL 积分异常只检测和留痕，不自动修改积分。

### 7.2 注销清零

```text
1. 开启 MySQL 事务。
2. 锁定 benefit_points_account 行。
3. 如果 available_points > 0，插入 ACCOUNT_CANCEL_CLEAR 流水。
4. 更新 available_points = 0，status = CANCELLED。
5. 提交事务。
```

说明：

- 注销不删除签到记录和积分流水。
- `status = CANCELLED` 后签到接口必须拒绝。
- 签到和注销并发时，通过锁定 `benefit_points_account` 行串行化；注销先完成则签到失败，签到先完成则注销清零包含本次签到在内的余额。

---

## 8. 落库状态与后续 TODO

1. DDL 已合并到：

```text
calculate/src/main/resources/sql/calculate_schema.sql
```

2. 测试 schema 已同步：

```text
calculate/src/test/resources/schema-test.sql
```

3. 已有 calculate 数据库的独立增量 SQL 已补充：

```text
calculate/src/main/resources/sql/calculate_benefit_reconciliation_patch.sql
```

4. 异常补偿任务已经实施：

```text
MySQL 提交成功但 Redis SETBIT 失败时，当前实时链路记录警告日志。
XXL-Job 按 MySQL 补齐或重建 Bitmap，并检测三表差异。
MySQL 积分异常只留痕，不由任务自动修改。
任务执行状态使用 XXL-Job 日志，不创建独立批次表。
```

详见《乘客端_福利签到_异常补偿_TECH.md》。
