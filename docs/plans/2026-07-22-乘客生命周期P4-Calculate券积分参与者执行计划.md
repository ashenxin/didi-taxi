# 乘客生命周期 P4：Calculate 券积分参与者执行计划

## 1. 计划状态

- 状态：P4 代码实现、P6 编排接入和五模块回归已完成；目标环境的 patch/backfill
  执行结果与投影覆盖率尚未在本文留证，写门禁继续保持 `SHADOW`
- 前置阶段：P1、P2、P3 已完成代码实现
- 本阶段模块：`calculate`
- 联动但暂不切流模块：`passenger`、`passenger-api`、`order`
- 实施方式：按任务顺序执行；每个任务先补测试，再实现，再执行聚焦验证
- 历史阶段约束：P4 当时只建设参与者能力；真实编排已在 P6 接通，旧 settings
  编排已在 P7 收束到独立 Legacy Adapter

### 1.1 本次执行结果（2026-07-22）

- 已完成 Calculate 生命周期投影、事件 Inbox、参与者 Inbox、内部接口认证、写栅栏、券积分参与者、结果查询、指标和异常契约。
- 已完成优惠券与积分现有写入口接入；生产默认使用 `SHADOW`，待 P6 持续投影 ACTIVE/CANCELLING/CANCELLED 后再评估切换 `ENFORCE`。
- 已生成数据库补丁与版本感知回填检查 SQL，但未自动连接或修改实际数据库。
- `mvn -pl calculate verify` 通过：66 个测试通过，覆盖率门禁通过。
- `mvn -pl calculate,order,passenger-api,passenger -am verify` 通过：Calculate 66、Passenger 158、Order 106、Passenger API 160，共 490 个测试通过。
- 按约定跳过 Docker 环境验证。

### 1.2 后续阶段复核（2026-07-27）

- P6 已接通 Passenger 编排、Kafka 命令/回执、超时查询和生命周期持续投影。
- P7 已将旧 settings 主服务中的券积分注销编排移出，旧路径仅保留在独立
  `LegacyAccountCancellationAdapter` 作为灰度回退通道。
- 最新五模块测试库存为 558：Calculate 66、Passenger 177、Order 106、
  Wallet 35、Passenger API 174；这是后续阶段的累计回归证据，不覆盖下面的 P4
  历史验证记录。
- 本仓无法证明目标数据库已经执行
  `calculate_account_lifecycle_p4_patch.sql` 与
  `calculate_account_lifecycle_p4_backfill.sql`；切换 `ENFORCE` 前仍须执行覆盖率、
  状态、版本和重复事件核验。

## 2. 目标

P4 将 Calculate 领域收束为一个标准生命周期参与者，负责：

1. 注销最终检查：检查乘客是否存在锁定优惠券。
2. 注销资产处理：作废未使用券，清零可用积分并关闭积分账户。
3. 本地事务围栏：注销开始后禁止新领券、新增积分和新锁券。
4. 命令幂等：使用 `operationNo + stepCode` 保证重复命令不重复产生副作用。
5. 成功未知恢复：响应丢失后可查询已经提交的参与者结果。
6. 审计保留：不删除券、签到、积分和用券流水。

换号不调用上述清理步骤，不迁移、不清空 Calculate 资产，因为 `customer.id` 保持不变。

## 3. 非目标

P4 不包含：

- 不实现 Passenger 主编排器对三个 Calculate 步骤的真实调度；归 P6。
- 不发布或消费 Kafka 生命周期命令/结果；可靠发布、主动查询调度和恢复归 P6。
- 不删除 `PassengerSettingsService` 中现有锁券检查、券作废和积分清零调用；归 P7。
- 不处理订单未结算、支付、退款和免密协议；分别归 Order 和 Wallet。
- 不删除历史券、积分流水、签到记录或对账问题记录。
- 不建立通用责任链框架，不让 HTTP 拦截器承担数据库事务围栏。
- 不在 P4 抽取新的公共 Maven Starter；待 P5 完成后再评估稳定公共契约。

## 4. 冻结业务决策

### 4.1 步骤契约

| stepCode | 类型 | 语义 | 成功结果 |
|---|---|---|---|
| `CALCULATE_FINAL_CHECK` | 同步最终检查 | 建立 Calculate 本地栅栏并检查锁定券 | `PASS` 或 `BLOCKED` |
| `CALCULATE_INVALIDATE_UNUSED_COUPONS` | 幂等动作 | 作废全部 `UNUSED` 优惠券 | `PASS` 和作废数量 |
| `CALCULATE_CLEAR_POINTS` | 幂等动作 | 可用积分归零并关闭积分账户 | `PASS` 和清零摘要 |

未知 `stepCode` 必须在写 Inbox、修改投影或修改领域资产之前返回请求错误。

### 4.2 写操作矩阵

| 动作 | ACTIVE | CANCELLING | CANCELLED |
|---|---:|---:|---:|
| 查询券、积分、计价 | 允许 | 允许 | 允许只读 |
| 领取优惠券 | 允许 | 拒绝 | 拒绝 |
| 福利签到/增加积分 | 允许 | 拒绝 | 拒绝 |
| 新订单锁券 | 允许 | 拒绝 | 拒绝 |
| 已锁券核销 | 允许 | 允许消解存量 | 原则上不应到达，允许幂等回放 |
| 已锁券释放 | 允许 | 允许消解存量 | 允许补偿回放 |
| 生命周期券作废 | 普通入口禁止 | 只允许当前 operation | 只允许同命令幂等回放 |
| 生命周期积分清零 | 普通入口禁止 | 只允许当前 operation | 只允许同命令幂等回放 |

### 4.3 数据保留

- `user_coupon` 保留原记录，以状态 `INVALID` 表示注销作废。
- `coupon_use_record` 每张实际作废的券写一条 `INVALIDATE` 流水。
- `benefit_points_account.available_points` 归零。
- `total_earned_points`、`total_used_points` 保留。
- `total_cleared_points` 累加本次清零值。
- 正余额清零时写 `ACCOUNT_CANCEL_CLEAR` 负向流水。
- 零余额也必须由参与者 Inbox 留下执行审计，不伪造零积分流水。
- `benefit_sign_record` 和 `benefit_reconciliation_issue` 不删除。

## 5. 总体事务模型

### 5.1 统一锁顺序

所有涉及乘客资产状态的事务统一按以下顺序获取锁：

```text
calculate_lifecycle_participant_inbox(operation_no, step_code)（仅生命周期命令）
    -> calculate_account_lifecycle_projection(customer_id)
    -> benefit_points_account(customer_id) 或 user_coupon
    -> 领域流水
    -> calculate_lifecycle_participant_inbox 结果更新（仅生命周期命令）
```

普通资产写事务不访问参与者 Inbox，从生命周期投影开始获取锁。禁止业务方法先锁积分/优惠券，再反向锁生命周期投影，避免死锁。

### 5.2 最终检查事务

```text
校验命令
  -> 以 operationNo + stepCode 查询/占位 Inbox
  -> 锁定并应用 CANCELLING 投影
  -> 查询 LOCKED 券
  -> 写 PASS/BLOCKED 和 blocker_snapshot
  -> 提交
```

投影更新和锁券检查必须在同一事务内。即使结果为 `BLOCKED`，本地投影仍保持 `CANCELLING`，防止出现新的券或积分；后续撤销/恢复 ACTIVE 由 P6 生命周期事件处理。

### 5.3 资产动作事务

```text
校验 operationNo + stepCode
  -> 查询/占位 Inbox
  -> 锁定投影并校验 CANCELLING + operationNo 一致
  -> 执行领域副作用和流水
  -> 写 result_snapshot
  -> 标记 Inbox COMPLETED
  -> 提交
```

Inbox、领域副作用和结果必须同事务提交。事务失败时不得遗留 `PROCESSING` 占位。

## 6. 数据库变更

### 任务 1：建立 P4 增量 SQL 和测试 Schema

新增：

- `calculate/src/main/resources/sql/calculate_account_lifecycle_p4_patch.sql`
- `calculate/src/main/resources/sql/calculate_account_lifecycle_p4_backfill.sql`
- `calculate/src/test/java/com/sx/calculate/lifecycle/CalculateLifecycleSchemaContractTest.java`

修改：

- `calculate/src/main/resources/sql/calculate_schema.sql`
- `calculate/src/test/resources/schema-test.sql`

新增三张表：

#### `calculate_account_lifecycle_event_inbox`

用途：生命周期投影事件永久去重和同事件 ID 异参冲突检测。

关键字段：

```text
source_event_id PK
customer_id
lifecycle_version
request_hash
created_at
```

#### `calculate_account_lifecycle_projection`

用途：Calculate 本地事务围栏的权威输入。

关键字段：

```text
customer_id PK
business_status
lifecycle_status
lifecycle_version
operation_no
source_event_id UNIQUE
row_version
updated_at
```

#### `calculate_lifecycle_participant_inbox`

用途：保存三个步骤的命令摘要、状态和永久结果。

关键字段：

```text
id PK
operation_no
step_code
customer_id
lifecycle_version
request_hash
status
decision
blocker_snapshot JSON
result_snapshot JSON
created_at
updated_at
UNIQUE(operation_no, step_code)
```

`status` 本阶段只允许 `PROCESSING/COMPLETED`；正常事务回滚后不保留 PROCESSING。`decision` 允许 `PASS/BLOCKED/UNKNOWN`，但数据库或基础设施异常通过异常返回，不能伪装成 PASS。

回填 SQL 从 `passenger.customer` 向 Calculate 投影写入当前快照，并使用稳定、可重算的 `source_event_id` 和请求摘要。重复执行必须幂等，版本较旧的数据不得覆盖较新投影。

回填核验 SQL 必须包含：

```sql
-- 缺失投影数
-- customer 与 projection 生命周期版本不一致数
-- customer 与 projection 状态不一致数
-- 重复 source_event_id 数
```

聚焦验证：

```bash
mvn -pl calculate -Dtest=CalculateLifecycleSchemaContractTest test
```

提交建议：

```text
功能：新增Calculate生命周期参与者表结构
```

## 7. 投影与命令基础设施

### 任务 2：实现投影模型、版本校验和事件去重

新增包：

```text
calculate/src/main/java/com/sx/calculate/lifecycle/
```

新增主要文件：

- `model/CalculateAccountLifecycleProjection.java`
- `model/CalculateAccountLifecycleEventInbox.java`
- `model/ApplyCalculateLifecycleProjectionCommand.java`
- `model/CalculateLifecycleStatus.java`
- `dao/CalculateAccountLifecycleProjectionMapper.java`
- `dao/CalculateAccountLifecycleEventInboxMapper.java`
- `service/CalculateLifecycleProjectionService.java`
- `service/CalculateLifecycleRequestHasher.java`
- `service/ProjectionApplyResult.java`
- `exception/CalculateLifecycleProjectionConflictException.java`
- `exception/CalculateLifecycleParticipantUnavailableException.java`

测试：

- `CalculateLifecycleProjectionServiceTest`
- `CalculateLifecycleProjectionIntegrationTest`

必须覆盖：

1. 首次插入 ACTIVE 投影。
2. 更高版本覆盖旧版本。
3. 低版本事件忽略且不回退状态。
4. 同版本同内容幂等。
5. 同版本不同内容冲突。
6. 同 `sourceEventId` 同摘要幂等。
7. 同 `sourceEventId` 异参冲突。
8. 投影更新和事件 Inbox 同事务回滚。
9. 摘要使用长度前缀，避免简单字符串拼接歧义。

投影语义与 Order P3 保持一致，但代码暂不通过复制整个 Order 包来形成伪公共层。P5 完成后统一评估抽取 DTO、摘要器、认证和指标公共组件。

聚焦验证：

```bash
mvn -pl calculate -Dtest='CalculateLifecycleProjectionServiceTest,CalculateLifecycleProjectionIntegrationTest' test
```

提交建议：

```text
功能：实现Calculate生命周期本地投影
```

### 任务 3：实现生命周期内部认证

新增：

- `security/CalculateLifecycleInternalAuthProperties.java`
- `security/CalculateLifecycleInternalAuthFilter.java`
- `security/CalculateLifecycleSecurityStartupValidator.java`

修改：

- `calculate/src/main/resources/application.yml`
- `calculate/src/main/resources/application-local.yml`
- `calculate/src/test/resources/application-test.yml`

配置统一读取既有内部服务 Token，不新增第二套业务密钥。只保护：

```text
/api/v1/internal/account-lifecycle/calculate/**
```

要求：

- 缺 Token 返回 401。
- 错 Token 返回 401。
- 正确 Token 放行。
- 非生命周期接口不受该过滤器影响。
- `local/dev/test` 可使用本地配置；生产及未指定 profile 禁止空值和默认值。
- Token、摘要、手机号不得写日志或指标标签。

测试：

- `CalculateLifecycleInternalAuthFilterTest`
- `CalculateLifecycleSecurityStartupValidatorTest`

提交建议：

```text
安全：保护Calculate生命周期内部接口
```

## 8. 领域事务写围栏

### 任务 4：实现 Calculate 写围栏

新增：

- `model/CalculateWriteAction.java`
- `service/CalculateAccountWriteFence.java`
- `exception/CalculateLifecycleBlockedException.java`
- `exception/CalculateLifecycleUnknownException.java`

围栏提供三个明确入口，不使用模糊布尔参数：

```text
lockAndRequireActive(customerId, action)
lockAndRequireResolvable(customerId, action)
lockAndRequireCurrentCancellation(customerId, operationNo, stepCode)
```

语义：

- `RequireActive`：领券、签到、新锁券。
- `RequireResolvable`：核销和释放已经存在的锁券，允许 ACTIVE/CANCELLING。
- `RequireCurrentCancellation`：两个生命周期清理步骤，必须匹配当前 operation。
- 投影缺失、状态未知、版本异常统一 fail-close。

测试：

- `CalculateAccountWriteFenceTest`
- `CalculateAccountWriteFenceIntegrationTest`
- `CalculateAccountLifecycleConcurrencyTest`

并发测试至少包含：

1. 最终检查与领券并发，不能在检查通过后新增 UNUSED 券。
2. 最终检查与新锁券并发，不能在检查通过后新增 LOCKED 券。
3. 最终检查与签到并发，不能在检查通过后增加积分。
4. CANCELLING 期间仍可释放已有锁券。
5. 投影行和积分账户统一锁顺序不发生测试级死锁。

### 任务 5：把围栏接入现有领域事务

修改：

- `calculate/src/main/java/com/sx/calculate/service/CouponService.java`
- `calculate/src/main/java/com/sx/calculate/service/BenefitService.java`
- `calculate/src/test/java/com/sx/calculate/service/CouponServiceTest.java`
- `calculate/src/test/java/com/sx/calculate/service/BenefitServiceTest.java`

接入点：

| 方法 | 围栏策略 |
|---|---|
| `claimAll` | 事务开始时 `RequireActive` 一次 |
| `claimSelected` | 事务开始时 `RequireActive` 一次 |
| `lock` | 已存在同订单锁券允许幂等读取；产生新锁券前 `RequireActive` |
| `use` | `RequireResolvable`，用于完成存量义务 |
| `release` | `RequireResolvable`，用于释放存量义务 |
| `signIn` | 在读取“今日已签到”快捷返回之前执行 `RequireActive` |

禁止在每张券循环里重复锁投影。一个批量领券请求只锁一次投影。

上线模式：

- P4 代码完成时支持 `OFF/SHADOW/ENFORCE` 三态配置。
- 在 ACTIVE 投影持续同步能力由 P6 接通之前，生产不得仅凭一次性回填直接启用 ENFORCE；否则回填后新注册乘客会因投影缺失被误拦截。
- 单元和集成测试以 ENFORCE 验证安全语义。
- P6 接通注册、恢复 ACTIVE 和注销事件后，完成覆盖率核验再切 ENFORCE。

聚焦验证：

```bash
mvn -pl calculate -Dtest='CouponServiceTest,BenefitServiceTest,CalculateAccountWriteFenceTest,CalculateAccountWriteFenceIntegrationTest,CalculateAccountLifecycleConcurrencyTest' test
```

提交建议：

```text
功能：为Calculate资产写入增加生命周期围栏
```

## 9. Calculate 生命周期参与者

### 任务 6：实现只读预检和最终检查

新增：

- `model/CalculateLifecyclePrecheckRequest.java`
- `model/CalculateLifecycleCommand.java`
- `model/CalculateLifecycleDecision.java`
- `model/CalculateLifecycleBlocker.java`
- `model/CalculateLifecycleParticipantResult.java`
- `model/CalculateLifecycleParticipantInbox.java`
- `dao/CalculateLifecycleParticipantInboxMapper.java`
- `service/AccountLifecycleCalculateParticipantService.java`
- `exception/CalculateLifecycleCommandConflictException.java`

先调整 `CouponService`，新增不会通过异常表达正常 blocker 的领域查询：

```text
inspectLockedCoupons(customerId)
```

预检：

- 只读，不建栅栏，不写 Inbox。
- 有锁定券返回结构化 `BLOCKED`。
- 数据库异常返回服务不可用，不得返回 PASS。

最终检查：

- 只接受 `CALCULATE_FINAL_CHECK`。
- 以 `operationNo + stepCode` 幂等。
- 同键同摘要返回永久结果。
- 同键异参返回 409 冲突。
- 应用 CANCELLING 投影后检查 LOCKED 券。
- blocker 固定包含 `code/resourceType/resourceNo/action`。

固定 blocker：

```text
code         = LOCKED_COUPON
resourceType = COUPON
resourceNo   = locked_order_no；缺失时使用非敏感券业务标识
action       = COMPLETE_OR_CANCEL_ORDER
```

测试：

- `AccountLifecycleCalculateParticipantServiceTest`
- `AccountLifecycleCalculateParticipantIntegrationTest`

必须覆盖 PASS、BLOCKED、UNKNOWN/异常、命令重放、异参冲突、结果序列化失败回滚、响应丢失后的结果查询。

### 任务 7：实现未使用券作废动作

调整：

- `CouponService.invalidateByPassenger`
- `CouponInvalidateRequest` 或由生命周期内部命令替代旧 DTO

要求：

1. 生命周期入口必须先命中 Inbox 幂等。
2. 必须校验当前投影属于同一个注销 operation。
3. 作废前再次确认没有 LOCKED 券。
4. 只把 `UNUSED` 更新为 `INVALID`。
5. 每张实际更新成功的券写 `INVALIDATE` 流水。
6. `invalid_reason=ACCOUNT_CANCEL`。
7. 参与者结果记录 `invalidatedCount`。
8. 重复命令不重复写流水。
9. 中途异常时券状态、券流水和 Inbox 一起回滚。

如果需要关联生命周期操作号，优先把非敏感 `operationNo` 写入动作流水的新增可空关联字段；不得把完整命令 JSON 填进 `reason`。如新增字段，必须同步 canonical schema、增量 SQL、H2 schema 和数据模型。

测试：

- 无券返回成功且数量为 0。
- 多张 UNUSED 全部作废。
- LOCKED、USED、EXPIRED、INVALID 不被修改。
- 存在 LOCKED 券时动作失败且不部分作废。
- 同命令重复执行结果一致且无重复流水。
- 同 operation/step 异参冲突。

### 任务 8：实现积分清零动作

调整：

- `BenefitService.clearPointsForAccountCancel`
- `BenefitClearPointsRequest` 或由生命周期内部命令替代旧 DTO

要求：

1. 使用 `operationNo + stepCode` 作为参与者幂等主键。
2. 积分流水 `biz_id` 使用稳定的 `operationNo:stepCode`。
3. 正余额写一条负向流水，余额归零。
4. 零余额不伪造流水，但参与者 Inbox 记录执行结果。
5. 账户不存在时创建 `CANCELLED` 零余额账户，阻止后续签到重新创建 ACTIVE 账户。
6. 保留累计获得和累计使用数据。
7. 重复命令不重复增加 `total_cleared_points`。
8. 参与者结果记录 `clearedPoints/accountStatus/pointsFlowId`。
9. 任何异常时账户、流水和 Inbox 一起回滚。

测试：

- 正余额清零一次。
- 零余额关闭账户。
- 不存在账户时建立 CANCELLED 空账户。
- 已 CANCELLED 同命令重放。
- 并发签到与清零只能按投影锁顺序得到一个合法结果。
- 同 operation/step 异参冲突。

聚焦验证：

```bash
mvn -pl calculate -Dtest='AccountLifecycleCalculateParticipantServiceTest,AccountLifecycleCalculateParticipantIntegrationTest,CalculateAccountLifecycleConcurrencyTest' test
```

提交建议：

```text
功能：实现Calculate生命周期券积分参与者
```

## 10. HTTP、结果查询和观测

### 任务 9：实现统一内部接口

新增：

- `controller/AccountLifecycleCalculateParticipantController.java`

接口：

```text
POST /api/v1/internal/account-lifecycle/calculate/precheck
POST /api/v1/internal/account-lifecycle/calculate/fence
POST /api/v1/internal/account-lifecycle/calculate/actions
GET  /api/v1/internal/account-lifecycle/calculate/results/{operationNo}/{stepCode}
```

约束：

- `/fence` 只接受 `CALCULATE_FINAL_CHECK`。
- `/actions` 只接受两个资产动作 stepCode。
- 查询不存在返回 404，不返回伪 UNKNOWN。
- 已完成结果永久可查。
- 参数错误 400、命令冲突 409、生命周期阻断返回结构化业务结果、基础设施异常 503。
- Controller 不直接调用 Coupon/Benefit 领域服务，只调用统一参与者服务。

旧接口：

```text
/internal/calculate/coupons/locked-exists
/internal/calculate/coupons/invalidate-by-passenger
/internal/calculate/benefits/points/clear-by-account-cancel
```

P4 期间保留，供旧 settings 流程兼容；标记为待 P7 删除。新生命周期代码不得调用旧接口。

### 任务 10：增加指标和安全日志

新增：

- `metrics/CalculateLifecycleMetrics.java`

指标至少包括：

```text
calculate.lifecycle.participant.command
calculate.lifecycle.write_fence
calculate.lifecycle.projection.apply
calculate.lifecycle.result.query
```

允许标签：

```text
stepCode（白名单归一化）
decision
action
result
```

禁止标签：

```text
operationNo
customerId
手机号
orderNo
couponId
异常 message
Token
```

日志可以包含 customerId、operationNo、stepCode 用于受控排障，但不得包含 Token、手机号、请求摘要原文或完整资产快照。

测试：

- `AccountLifecycleCalculateParticipantControllerTest`
- `CalculateLifecycleMetricsTest`

提交建议：

```text
功能：补充Calculate生命周期接口与观测
```

## 11. 验收测试矩阵

### 11.1 功能测试

| 场景 | 预期 |
|---|---|
| 无锁券最终检查 | PASS |
| 有锁券最终检查 | BLOCKED，返回稳定 blocker |
| 相同命令重复最终检查 | 返回同一结果，不重复执行 |
| 同 operation/step 改 customer/version | 409 |
| 多张未使用券作废 | 全部 INVALID，逐张留流水 |
| 券作废响应丢失后查询 | 查询得到原处理数量 |
| 正积分清零 | 余额 0、账户 CANCELLED、单条负向流水 |
| 零积分清零 | 账户 CANCELLED、无伪造积分流水 |
| 积分清零响应丢失后查询 | 查询得到原清零摘要 |
| 换号 | 不调用 Calculate 清理，券积分保持不变 |
| 查询历史券/积分流水 | 注销后仍可审计 |

### 11.2 并发测试

| 竞态 | 不变量 |
|---|---|
| 最终检查 vs 新领券 | 不能出现 PASS 后新增 UNUSED |
| 最终检查 vs 新锁券 | 不能出现 PASS 后新增 LOCKED |
| 最终检查 vs 签到 | 不能出现 PASS 后增加积分 |
| 券作废 vs 重复券作废 | 每张券最多一条本次注销 INVALIDATE 流水 |
| 积分清零 vs 重复清零 | 只清一次，不重复累计 cleared |
| CANCELLING vs 释放锁券 | 允许释放，后续新操作重新发起最终检查 |

### 11.3 故障测试

- Inbox 插入失败：领域资产不改变。
- 投影更新失败：不执行领域动作。
- 券流水插入失败：券状态全部回滚。
- 积分流水插入失败：积分账户回滚。
- 结果序列化/写入失败：领域动作回滚。
- HTTP 响应在事务提交后丢失：结果查询可恢复。
- 数据库不可用：返回 UNKNOWN/503 语义，不能按 PASS 处理。

### 11.4 真实 MySQL 验证

H2 测试通过后，在 MySQL 8 执行：

1. 增量建表。
2. ACTIVE 投影回填。
3. 覆盖率和一致性查询。
4. 两连接并发执行最终检查与领券。
5. 两连接并发执行最终检查与锁券。
6. 两连接并发执行清积分与签到。
7. 检查死锁日志和最终不变量。

不使用 Docker 作为本阶段验收前置；沿用用户已确认的本地/现有 MySQL 环境。

## 12. 最终验证命令

聚焦测试：

```bash
mvn -pl calculate test
```

模块验证：

```bash
mvn -pl calculate verify
```

受影响链路验证：

```bash
mvn -pl calculate,order,passenger-api,passenger -am verify
```

静态检查：

```bash
git diff --check
rg -n "token|password|otp|phone" calculate/src/main/java/com/sx/calculate/lifecycle calculate/src/main/resources/sql/calculate_account_lifecycle_p4_patch.sql
rg -n "CALCULATE_FINAL_CHECK|CALCULATE_INVALIDATE_UNUSED_COUPONS|CALCULATE_CLEAR_POINTS" passenger/src/main/resources/account-lifecycle calculate/src/main
```

验收证据必须记录：

- 测试数量和失败数量。
- JaCoCo 是否通过。
- SQL 与 H2 schema 一致性。
- MySQL 回填覆盖率。
- 三组并发测试最终数据。
- `git diff --check` 结果。
- 新增指标、错误码和接口清单。

## 13. 上线顺序

1. 部署 SQL，不启用写围栏。
2. 执行 ACTIVE 投影回填。
3. 执行缺失、状态、版本和重复事件核验，必须为 0。
4. 部署 P4 代码，参与者接口暂不接真实注销流量。
5. 写围栏先进入 SHADOW，观察投影缺失、状态差异和潜在死锁。
6. P6 接通新注册 ACTIVE、注销 CANCELLING、撤销恢复 ACTIVE、最终 CANCELLED 的持续投影事件。
7. 再次全量回填和覆盖率核验。
8. 将 Calculate 写围栏切换为 ENFORCE。
9. P6 编排器接入三个 Calculate 步骤。
10. P7 灰度完成后删除旧 BFF 直接清理调用和旧 Calculate 注销内部接口。

## 14. 回滚与前向恢复

- P4 未接真实编排前，可以停止参与者新流量。
- 写围栏在 SHADOW 阶段可切回 OFF；进入 ENFORCE 且真实注销开始后，不得无条件关闭高风险写保护。
- 已提交的参与者 Inbox 结果必须永久可查询，不得因回滚删除。
- 已作废优惠券和已清零积分属于不可逆业务动作，不通过数据库脚本反向恢复。
- 已进入不可逆阶段的 Operation 只能由 P6 前向恢复完成。
- SQL 回滚不删除三张表；停用代码即可，保留审计和幂等数据。

## 15. P4 完成定义

以下条件全部满足才算 P4 完成：

1. 三张生命周期表和回填脚本完成并通过结构契约测试。
2. 投影版本、事件去重和异参冲突规则通过测试。
3. 领券、签到、新锁券全部接入事务写围栏。
4. 三个冻结 stepCode 全部通过统一参与者入口执行。
5. 重复命令不重复作废券、不重复清积分。
6. 响应丢失后可以通过结果接口恢复成功结果。
7. 券和积分历史审计链完整保留。
8. 换号路径不调用任何 Calculate 清理动作。
9. H2、MySQL 并发和受影响模块验证全部通过。
10. P4 尚未接管真实注销，旧路径保留到 P7。
