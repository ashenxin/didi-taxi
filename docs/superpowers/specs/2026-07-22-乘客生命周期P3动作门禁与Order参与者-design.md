# 乘客生命周期 P3：动作门禁、影子预检与 Order 参与者设计

## 1. 背景与结论

P1 已建立生命周期计划、运行快照、状态机以及 Operation/Step/Event/Outbox 持久化；P2 已将
`customer.auth_epoch` 设为认证权威，完成按用途原子 OTP、普通/受限会话、换号事务、注销建栅栏以及
本节点 WebSocket 撤销边界。

P3 解决两个尚未闭合的问题：

1. `CANCELLING` 乘客只能完成查询、取消订单和支付欠款，不能再创建新的订单或资金义务。
2. 注销对 Order 的检查必须成为稳定参与者契约，而不是由 passenger-api 或生命周期服务读取 Order
   内部表、复制 SQL 或散落编排逻辑。

本阶段采用已经确认的方案 A：

- passenger-api 使用统一动作码矩阵快速拒绝不允许的请求。
- Order 保存本地乘客生命周期投影，并由 `AccountWriteFence` 在领域写入口最终执法。
- Order 的硬门禁直接生效，不以影子结果决定是否创建订单。
- 旧注销检查与新 Order 预检并行做影子比较，但旧 settings 的正式迁移仍属于 P7。
- Kafka 发布、跨服务恢复、人工处置和完整 Saga 属于 P6。

## 2. 范围

### 2.1 P3 实现

- 稳定动作码、路径解析器和动作权限矩阵。
- passenger-api 快速门禁与一致的 403/503 响应。
- Order 生命周期投影、投影版本保护和 `AccountWriteFence`。
- 创建订单与注销栅栏针对同一乘客的串行并发协议。
- Order 注销参与者的同步预检、建栅栏命令、幂等 Inbox 和结果查询。
- 稳定的 `PASS/BLOCKED/UNKNOWN` 与结构化 blocker。
- 旧注销检查与新预检的影子差异指标。
- ACTIVE 投影回填、覆盖率核验和直接执法上线检查单。

### 2.2 P3 不实现

- 不公开新的换号或注销 Controller。
- 不切换旧 settings 到完整生命周期 Saga。
- 不调用 calculate、wallet 执行资产清理；它们分别属于 P4、P5。
- 不实现 passenger Outbox 发布器、Kafka 结果消费者、XXL 恢复或人工处置；它们属于 P6。
- 不处理跨实例 WebSocket 撤销；它属于 P6。
- 不重构手机号明文/密文策略。

## 3. 总体架构

```text
乘客 HTTP 请求
    │
    ▼
passenger-api JWT + DB auth state
    │
    ├── PassengerActionResolver：method + 规范化路径 -> actionCode
    ├── PassengerActionPolicy：businessStatus + lifecycleStatus + scope + actionCode
    │       ├── ALLOW：继续
    │       ├── DENY：403
    │       └── UNKNOWN：503
    ▼
order 内部接口
    │
    ├── OrderAccountLifecycleProjection
    ├── AccountWriteFence（最终执法）
    └── AccountLifecycleOrderParticipant（预检/建栅栏/结果查询）
```

passenger-api 只负责快速拒绝和用户体验；Order 本地投影与写栅栏是不可绕过的最终边界。内部调用、延迟
请求或未来绕过 BFF 的调用都必须经过 `AccountWriteFence`。

## 4. 动作码与权限矩阵

沿用总设计中的稳定动作码，不使用 Controller 名或 URL 作为业务规则：

- `RIDE_CREATE`
- `ORDER_READ`
- `ORDER_CANCEL`
- `DEBT_PAYMENT`
- `COUPON_CLAIM`
- `BENEFIT_SIGN_IN`
- `AUTO_PAY_SIGN`
- `REFUND_READ`
- `PROFILE_READ`
- `SESSION_LOGOUT`
- `WS_CONNECT`
- `WALLET_READ`
- `COUPON_READ`
- `BENEFIT_READ`
- `PHONE_CHANGE`
- `ACCOUNT_CANCEL`
- `AUTO_PAY_MANAGE`

P3 的最小矩阵如下：

| businessStatus | lifecycleStatus | tokenScope | actionCode | 结果 |
|---|---|---|---|---|
| `0` | `ACTIVE` | `NORMAL` | 已知动作 | `ALLOW` |
| `0` | `CANCELLING` | `LIFECYCLE_RESTRICTED` | `ORDER_READ` | `ALLOW` |
| `0` | `CANCELLING` | `LIFECYCLE_RESTRICTED` | `ORDER_CANCEL` | `ALLOW` |
| `0` | `CANCELLING` | `LIFECYCLE_RESTRICTED` | `DEBT_PAYMENT` | `ALLOW` |
| `0` | `CANCELLING` | `LIFECYCLE_RESTRICTED` | `PROFILE_READ` | `ALLOW` |
| `0` | `CANCELLING` | `LIFECYCLE_RESTRICTED` | `ACCOUNT_CANCEL`（后续显式映射） | `ALLOW` |
| `0` | `CANCELLING` | `LIFECYCLE_RESTRICTED` | 其他动作 | `DENY` |
| 任意 | `CANCELLED` | 任意 | 任意 | `DENY` |
| 非 `0`/空 | 任意 | 任意 | 任意 | `DENY` |
| 任意未知值 | 任意未知值 | 任意未知值 | 任意 | `UNKNOWN` |

`PassengerActionResolver` 使用 `ServletRequestPathUtils.pathWithinApplication()` 和 `PathPattern`，不得重新
使用原始 `requestURI`、字符串前缀或 Controller 内 if/else。受限会话不再由零散 URL 白名单判断。

未映射的受限请求默认 `DENY`；未知认证事实或矩阵数据默认 `UNKNOWN`。ACTIVE/NORMAL 的现有受保护接口
必须全部纳入覆盖测试，避免因为漏配动作码造成静默放行或意外停机。

P3 当前不新增公开的注销查询/继续接口，因此 `ACCOUNT_CANCEL` 只冻结权限语义，不映射虚构 URL；后续
P6/P7 新增明确路径时才能加入 resolver。

## 5. Order 生命周期投影

新增 `order_account_lifecycle_projection`：

| 字段 | 约束与含义 |
|---|---|
| `customer_id` | 主键，与 passenger 的 `customer.id` 一致 |
| `business_status` | passenger 业务状态 |
| `lifecycle_status` | `ACTIVE/CANCELLING/CANCELLED` |
| `lifecycle_version` | passenger 生命周期版本，只接受递增 |
| `operation_no` | 当前生命周期操作号；ACTIVE 时可空 |
| `source_event_id` | 来源事件或命令 ID，用于去重 |
| `row_version` | Order 本地 CAS 版本 |
| `updated_at` | 更新时间 |

投影规则：

- `lifecycle_version` 小于当前值：拒绝为乱序。
- 版本相等且状态、operationNo、sourceEventId 一致：幂等成功。
- 版本相等但内容不同：冲突。
- 版本递增：在持有 customer 投影行锁时更新。
- `CANCELLED` 不能被相同或更低版本恢复。
- 投影缺失时，`RIDE_CREATE` 返回 `UNKNOWN`，失败关闭。

P3 提供回填 SQL/导入契约和覆盖率查询。生产启用直接执法前必须完成现有 customer 的 ACTIVE 投影回填，
并核对 passenger 有效 customer 数、Order 投影数、最大 customerId 和抽样版本。生产配置不得以
`SHADOW` 模式代替 Order 硬门禁；回填是上线前置步骤，不是运行时降级路径。

## 6. AccountWriteFence 与并发协议

`AccountWriteFence` 是 Order 的领域组件，只依赖投影 Mapper，不调用 passenger：

```java
WriteFenceDecision lockAndCheck(long customerId, OrderActionCode actionCode);
```

`RIDE_CREATE` 在订单事务中按以下顺序执行：

1. `SELECT ... FOR UPDATE` 锁定 customer 投影行。
2. 校验投影存在、业务状态有效、生命周期为 ACTIVE。
3. 检查该乘客正在处理或未结清的订单。
4. 写 `trip_order`、`order_event` 和 `order_outbox_event`。
5. 提交事务后释放投影行锁。

Order 注销建栅栏命令在另一个事务中执行：

1. 锁定同一 customer 投影行。
2. 按生命周期版本推进为 CANCELLING。
3. 在持锁事务中检查活动订单、欠款和结算未知状态。
4. 写 Inbox 最终结果与 blocker 快照。
5. 提交事务后释放投影行锁。

因此：

- 下单先获得锁：订单先提交；注销命令随后看到新订单并返回 `BLOCKED`。
- 注销命令先获得锁：CANCELLING 先提交；下单随后返回生命周期冲突。

禁止使用“事务外查状态、事务内直接插单”，也不采用跨库分布式事务。

## 7. Order 参与者契约

内部接口：

```text
POST /api/v1/internal/account-lifecycle/order/precheck
POST /api/v1/internal/account-lifecycle/order/fence
GET  /api/v1/internal/account-lifecycle/order/results/{operationNo}/{stepCode}
```

内部接口必须使用独立的 `ORDER_LIFECYCLE_INTERNAL_TOKEN`，不能复用乘客 JWT、
`PASSENGER_INTERNAL_TOKEN` 或外部注入的 `X-User-Id`。非 local/dev/test 环境沿用 P2 的严格规则：UTF-8
长度至少 32 bytes、拒绝首尾空白、不得包含 `change-me`、不得以开发前缀开头。

命令至少包含：

```text
operationNo, stepCode, customerId, targetLifecycleStatus,
lifecycleVersion, sourceEventId, requestedAt
```

`lifecycleVersion` 是 passenger 已提交后的权威目标版本，不在 Order 内用旧版本 `+1` 推算。

`order_lifecycle_participant_inbox` 使用 `(operation_no, step_code)` 唯一键，保存 requestHash、状态、结果和
结构化 blocker。相同 key + hash 返回原结果；相同 key + 不同 hash 返回 409。P3 命令同步完成，不创建
后台重试线程。

参与者响应固定为：

```json
{
  "decision": "PASS | BLOCKED | UNKNOWN",
  "blockers": [
    {
      "code": "ACTIVE_ORDER | UNPAID_ORDER | SETTLEMENT_UNKNOWN",
      "resourceType": "ORDER",
      "resourceNo": "稳定业务编号",
      "action": "CANCEL_ORDER | PAY_OUTSTANDING | CONTACT_OPERATIONS"
    }
  ]
}
```

响应不暴露 SQL、表名、异常 message、手机号、Token 或内部堆栈。基础设施异常、投影缺失或无法确认的
结算状态必须返回 `UNKNOWN`，不能错误返回 PASS。

## 8. 影子预检

旧 settings 注销检查仍决定当前线上响应。P3 在同一请求中调用新的只读 Order precheck，并比较：

- `MATCH`
- `LEGACY_ONLY`
- `NEW_ONLY`
- `ERROR`

影子调用超时、5xx、反序列化异常和差异均只记录固定低基数指标，不改变旧接口结果。日志可以记录
operationNo/订单号等已批准的业务审计标识，但不得记录手机号、Token、OTP、SQL 或异常 message。

影子 precheck 不更新生命周期投影、不写参与者 Inbox、不创建 Operation；它只是新旧检查语义的只读对比。

## 9. 状态传播与阶段边界

P3 的 Order 投影通过同步内部投影/建栅栏命令更新。P3 不声称已经具备 passenger Outbox 到 Kafka 的可靠
投递；Outbox 发布、消费重试和跨实例恢复在 P6 完成。

P2 新生命周期服务仍未公开，所以 P3 不会提前切换真实注销 Saga。直接执法的含义是：一旦 Order 接受
CANCELLING/CANCELLED 投影或 fence 命令，所有后续 `RIDE_CREATE` 都由领域门禁强制拒绝，而不是仅记录
影子结果。P7 的公开入口必须通过 P6 编排调用该参与者，禁止绕过它直接修改 customer。

## 10. 错误语义

- passenger-api 动作矩阵明确拒绝：HTTP 403。
- Order 生命周期投影为 CANCELLING/CANCELLED：HTTP/业务码 409，稳定错误码
  `ACCOUNT_LIFECYCLE_BLOCKED`。
- 投影缺失、状态未知、参与者超时或基础设施不可用：503，稳定错误码
  `ACCOUNT_LIFECYCLE_UNKNOWN`。
- Inbox 同键不同 hash：409，稳定错误码 `LIFECYCLE_COMMAND_CONFLICT`。
- blocker 是业务结果，不通过异常 message 承载。

## 11. 指标与审计

新增固定低基数指标：

- `passenger.lifecycle.action.decision{actionCode,decision}`
- `order.lifecycle.write_fence{actionCode,decision}`
- `order.lifecycle.projection.apply{result}`
- `order.lifecycle.participant.command{stepCode,decision}`
- `passenger.lifecycle.order_shadow{result}`

Tag 只能来自 enum/switch 白名单。customerId、operationNo、orderNo、手机号、Token、异常 message 不得进入
指标 tag。指标失败必须 best-effort，不影响业务事务。

## 12. 测试策略

### 12.1 passenger-api

- 参数化覆盖动作矩阵。
- context path、矩阵参数和编码路径均解析为同一 actionCode。
- CANCELLING restricted 可查询、取消、支付欠款，创建订单为 403。
- BFF 快速拒绝时不得调用 Order。
- auth state 5xx/超时仍为 503，不能用动作矩阵降级放行。
- 影子差异和影子异常不改变旧接口结果。

### 12.2 order

- 投影新增、幂等、乱序、同版本冲突和 CANCELLED 不倒退。
- 投影缺失时创建订单失败关闭。
- 两线程覆盖“下单先提交则注销 BLOCKED”和“注销先提交则下单 409”。
- 订单、Event、Outbox 与 Inbox 在故障注入时整体回滚。
- 同 operationNo + stepCode + hash 重放；不同 hash 冲突。
- blocker 顺序、code、action 稳定且不泄露内部实现。
- 内部接口缺少或错误服务 Token 时返回 401/403。

### 12.3 验收

- `passenger-api`、`order` 分模块 verify 通过。
- `mvn -pl passenger,passenger-api,order -am verify` 通过。
- JaCoCo 门禁通过。
- 静态扫描不存在 Controller/业务 Service 自写 lifecycle 状态分支。
- 不运行 Docker/Testcontainers；真实 MySQL 行锁并发压测记录为上线前验证项，不能用 H2 测试冒充。

## 13. 上线与回滚

上线顺序：

1. 执行 Order DDL。
2. 回填 ACTIVE 投影并核对覆盖率。
3. 部署 Order 参与者和硬门禁。
4. 部署 passenger-api 动作矩阵与影子预检。
5. 观察 UNKNOWN、生命周期拒绝、影子差异和 Order 创建错误率。

回滚允许关闭 passenger-api 快速门禁和影子调用；当投影已进入生产并由生命周期流程维护后，Order
`RIDE_CREATE` 硬门禁不得无条件关闭。已进入 CANCELLING 的 customer 只能撤销生命周期操作或前向完成，
不能通过删除投影、降低版本或恢复旧 Token 回滚。

## 14. P3 完成定义

- 动作权限只存在于统一矩阵，不散落到各 Controller。
- CANCELLING restricted 可以查询、取消订单和支付欠款，不能创建订单。
- Order 在本地、事务内、失败关闭地执行 `AccountWriteFence`。
- 下单与注销 fence 的并发竞态被同一投影行锁关闭。
- Order 参与者提供幂等命令、结果查询和结构化 blocker。
- 新旧注销检查具备影子差异证据但未提前切换旧 settings。
- P4、P5、P6、P7 的接口边界保持清晰。
