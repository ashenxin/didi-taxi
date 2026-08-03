# 数据库 SQL 脚本状态索引

本索引用于区分新库基线与存量库迁移，避免因为 `*_schema.sql` 已包含最终结构就提前删除
仍在承担升级职责的 patch。这里的状态描述仓库用途，不等于某个具体环境已经执行成功。

## 管理规则

- `*_schema.sql`：新数据库的完整目标结构；新增结构必须同步到这里。
- `*_seed.sql`：新数据库初始化数据；不得混入存量数据修复、回填或不可重复的业务更新。
- `*_patch.sql`：存量数据库增量迁移；所有环境执行并核验前保持 `ACTIVE`。
- `*_backfill.sql`：依赖真实存量数据和执行顺序，始终与 schema、seed 分离。
- `*_diagnostics.sql`：只读运维查询，不属于初始化和迁移。
- `SUPERSEDED`：被新的综合迁移完整覆盖，新环境不得再执行；暂留用于历史追溯。
- `ARCHIVE_CANDIDATE`：不再参与新部署，但需等所有环境确认后才能移动到 `sql/archive/`。

## Passenger

| 文件 | 状态 | 用途 |
|---|---|---|
| `passenger_schema.sql` | BASELINE | 新库完整结构，已包含生命周期字段和六张生命周期/历史表 |
| `passenger_seed.sql` | SEED | Passenger 初始化数据 |
| `passenger_account_lifecycle_patch.sql` | ACTIVE | 存量 Passenger 库升级；本地已执行，其他环境仍需分别确认 |
| `passenger_registration_lifecycle_outbox_patch.sql` | ACTIVE | 允许新注册账号在不创建 Lifecycle Operation 的情况下原子写入 ACTIVE 投影初始化 Outbox；本地已执行，其他环境仍需执行 |
| `passenger_phone_binding_history_backfill.sql` | BACKFILL | 补录生命周期上线后、注册链路修复前创建且缺少 ACTIVE 手机号绑定历史的账号；可重复执行 |
| `passenger_account_lifecycle_p6_diagnostics.sql` | OPERATIONAL | P6 只读诊断查询，不是上线 DDL |

## 跨领域生命周期修复

| 文件 | 状态 | 用途 |
|---|---|---|
| `passenger_lifecycle_completed_phone_change_projection_repair.sql` | OPERATIONAL | 修复已完成换号后 Order、Calculate、Wallet 投影仍残留操作号的问题；带候选数、更新数和一致性事务门禁 |

## Capacity

| 文件 | 状态 | 用途 |
|---|---|---|
| `capacity_schema.sql` | BASELINE | Capacity 新库完整结构 |
| `capacity_seed.sql` | SEED | Capacity 初始化数据 |

Capacity 当前没有增量 patch；不要为了统一形式创建空迁移文件。

## Order

| 文件 | 状态 | 用途 |
|---|---|---|
| `order_schema.sql` | BASELINE | 新库完整结构 |
| `order_seed.sql` | SEED | Order 初始化数据 |
| `order_account_lifecycle_p3_patch.sql` | ACTIVE | 存量库增加生命周期投影和参与者表 |
| `order_account_lifecycle_p3_backfill.sql` | BACKFILL | 从 Passenger 权威数据回填 Order 投影 |
| `order_trip_settlement_schema_sync_patch.sql` | ACTIVE | 补齐冻结计价、结算恢复字段及索引 |
| `order_settlement_payment_patch.sql` | SUPERSEDED | 已被综合同步补丁覆盖，只保留历史追溯 |
| `order_event_reason_desc_zh_patch.sql` | ARCHIVE_CANDIDATE | 一次性历史文案修正；确认所有环境执行后归档 |

## Calculate

| 文件 | 状态 | 用途 |
|---|---|---|
| `calculate_schema.sql` | BASELINE | 新库完整结构，已吸收当前四份结构补丁的目标状态 |
| `calculate_seed.sql` | SEED | Calculate 初始化数据 |
| `calculate_account_lifecycle_p4_patch.sql` | ACTIVE | 存量库生命周期参与者升级 |
| `calculate_account_lifecycle_p4_backfill.sql` | BACKFILL | 生命周期投影回填及覆盖率核验 |
| `calculate_benefit_reconciliation_patch.sql` | ACTIVE | 存量库增加福利对账异常表 |
| `calculate_coupon_identity_patch.sql` | ACTIVE | 存量库增加优惠券领取身份与注销字段 |
| `calculate_settlement_coupon_idempotency_patch.sql` | ACTIVE | 存量库增加完单锁券快照及唯一约束 |

## Wallet

| 文件 | 状态 | 用途 |
|---|---|---|
| `wallet_schema.sql` | BASELINE | 新库完整结构，已吸收当前三份结构补丁的目标状态 |
| `wallet_account_lifecycle_p5_patch.sql` | ACTIVE | 存量库生命周期投影、参与者和解约审计升级 |
| `wallet_account_lifecycle_p5_backfill.sql` | BACKFILL | 生命周期投影回填及覆盖率核验 |
| `wallet_payment_attempt_patch.sql` | ACTIVE | 存量支付表升级为一单多次支付尝试 |
| `wallet_payment_notification_patch.sql` | ACTIVE | 存量支付表增加结果通知恢复字段 |

Wallet 当前没有独立 seed 文件；不要为了目录形式完整而创建空 seed。

## XXL-JOB

| 文件 | 状态 | 用途 |
|---|---|---|
| `xxl-job-admin/src/main/resources/db/didi_taxi_wallet_job_seed.sql` | SEED | 注册 `wallet-executor` 与 `walletPaymentResultNotify`；执行器地址依赖自动注册，不写死环境地址 |

该任务负责把 Wallet 已持久化的支付结果可靠通知给 Order。缺失时支付单会停在
`notify_status=PENDING`，订单也会继续停在 `PAYMENT_REQUIRED`。本地紧急排障可以在
XXL-JOB 控制台把执行器改为手工地址 `http://127.0.0.1:9997`；生产应使用自动注册或
生产环境实际可达的执行器地址。

## 归档条件

脚本只有同时满足以下条件才可从当前目录移动到 `sql/archive/`：

1. 所有目标环境都有明确执行记录和核验结果。
2. 对应最终结构已经进入模块 `*_schema.sql`。
3. 没有测试、运行手册或部署流程继续把该脚本作为活动输入。
4. 脚本不再承担回填、诊断或失败恢复用途。

归档只移动历史迁移，不删除审计记录；移动时必须同步修改测试和文档引用。
