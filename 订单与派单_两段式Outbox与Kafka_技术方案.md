# 订单与派单：两段式 Outbox 与 Kafka 技术方案（MVP 可落地）

> 文档文件名：`订单与派单_两段式Outbox与Kafka_技术方案.md`  
> 范围：本方案用于将“下单同步派单”演进为“两段式：先创建订单、后异步派单”，并引入 Transactional Outbox + Kafka，消费者落在 `capacity-service`。  
> 约束：Kafka 消息 JSON；“无司机时不重试”（不让 Kafka 层反复投递该订单的派单请求）。  
> 代码现状：`order-service` 已有 `trip_order` 状态机与 `order_event` 流水；派单确认窗口（`PENDING_DRIVER_CONFIRM`）、超时扫描、改派调度等已实现；`passenger-api` 仍是同步 `createAndAssign` 编排。

---

## 0. 文档定位与关联

- 本文件是《`乘客司机端_最小闭环接口调用文档.md`》**§3.0「对内两段式」** 的**专项落地技术方案**（对外仍一步下单；对内拆为“创建订单 + 异步补指派”）。
- 与《`订单服务幂等与并发方案说明.md`》中 **Transactional Outbox** 思路一致；本文件补充到 **Kafka 分发 + `capacity-service` 消费** 的具体工程形态与契约。
- 与《`乘客司机端_Redis与听单下线策略.md`》中 **迟滞匹配/无司机兜底** 的关系：**无司机不重试** 后，仍由现状的迟滞匹配体系继续推进 `CREATED` 订单（本文件不替代该能力）。

---

## 1. 背景与问题

当前 `passenger-api` 下单链路是同步编排（路线/估价/找司机/创建订单/指派/打开确认窗口）。同步链路的问题：

- **时延不可控**：依赖多个下游，用户请求容易超时。
- **失败语义差**：部分成功/部分失败时不易补偿（例如订单创建成功但派单失败）。
- **重试危险**：客户端/网关重试可能造成重复创建/重复指派。
- **多实例扫描问题**：未来若引入定时扫描/补偿，`@Scheduled` 在多实例下可能重复执行。

目标是把“订单创建成功”与“派单任务产生”绑定为同一事务，再通过 Kafka 异步驱动派单。

---

## 2. 目标 / 非目标

### 2.1 目标

- **两段式**：下单请求只保证“订单创建成功”，派单在后台异步推进。
- **事务一致性**：订单创建成功时，必然产生可投递的派单事件（不丢）。
- **异步派单**：由 `capacity-service` 消费事件执行“找司机 → 指派 → 打开确认窗口”。
- **幂等**：允许 Kafka 至少一次投递；capacity 消费端对事件去重；order 侧状态机 CAS 作为最终门闩。
- **无司机不重试**：当时无司机时，capacity 消费端结束处理；后续由既有“迟滞匹配”兜底。
- **可观测**：能回答“为什么订单创建了却没派到司机”。

### 2.2 非目标（本期不强求）

- 生产级“恰好一次”投递语义（Exactly-once）。本期采用“至少一次 + 幂等”。
- 司机端 WebSocket 推送（另议题）。
- 立刻替换所有调度器到 XXL-JOB（可后续迁移）。

---

## 3. 术语

- **Outbox（事务外盒）**：在同一个数据库事务里写业务数据 + 写一条“待发布事件”。事务提交后，发布器扫描 outbox 并投递到 Kafka。
- **事件级幂等（capacity）**：同一 `eventId` 只处理一次（占坑去重）。
- **业务级幂等（order）**：状态机 CAS（如 `where status=CREATED`）保证重复调用不会推进错状态。

---

## 4. 全局架构与职责分工

### 4.1 服务职责

- **order-service**
  - 权威状态机（`trip_order`）与事件流水（`order_event`）
  - 在创建订单事务中写入 `order_outbox_event`
  - outbox 发布器：扫描 outbox → 投递 Kafka → 标记发布状态

- **capacity-service**
  - 维护司机池（Redis GEO）与候选过滤（在线/可接单/城市等）
  - Kafka 消费者：消费“需要派单”的事件，执行一次派单尝试
  - 消费端幂等：`processed_event`（去重表）记录 `eventId`
  - 无司机时结束，不重试；长期兜底交由既有迟滞匹配

- **passenger-api**
  - 对外下单入口语义调整：从“同步派单”变为“创建订单 + 派单异步推进”
  - 仍可保留 geocode/route/estimate（与派单可靠性不冲突）

### 4.2 为什么消费者放 capacity

司机池/GEO/筛选规则在 capacity，放 consumer 最顺手；order 仅负责状态机推进（CAS）。

---

## 5. Topic 与消息格式（JSON）

### 5.1 Topic

- `order.dispatch.requested.v1`

### 5.2 Key

- `orderNo`  
目的：同一订单事件在同一分区，方便后续扩展与排障。

### 5.3 Value（JSON Schema，MVP）

#### 5.3.1 字段契约表（`schemaVersion=1`）

> 设计原则：消息只用于触发 capacity 的“一次派单尝试”；订单权威状态以 `order-service` DB 为准。  
> 说明：`eventId` 在 JSON 中统一用 **string** 承载（避免大整数精度问题），其值与 `order_outbox_event.id` 字符串化一致。

| 字段 | 类型 | 必填 | 约束 / 说明 |
|------|------|------|-------------|
| `schemaVersion` | number | 是 | 固定为 `1`（本期只支持 1；其它值记日志并跳过，后续再定策略） |
| `eventId` | string | 是 | 非空；与 outbox 主键一一对应；用于 `capacity_processed_event` 去重 |
| `eventType` | string | 是 | 固定为 `ORDER_CREATED_NEED_DISPATCH` |
| `orderNo` | string | 是 | 非空；与 `trip_order.order_no` 一致 |
| `cityCode` | string | 是 | 非空；与下单城市一致 |
| `productCode` | string | 是 | 非空；产品线编码（如 ECONOMY） |
| `origin` | object | 是 | 必须包含 `lat` 与 `lng` |
| `origin.lat` | number | 是 | 纬度，范围 `[-90, 90]` |
| `origin.lng` | number | 是 | 经度，范围 `[-180, 180]` |
| `createdAt` | string | 是 | ISO-8601 时间字符串，仅用于排障/延迟统计；**不用于业务裁决** |
| `traceId` | string | 否 | 可选；用于链路排障，不参与业务 |
| `requestId` | string | 否 | 可选；与乘客请求幂等键对齐（若未来 passenger-api 提供） |

#### 5.3.2 反序列化与契约校验（capacity 消费端，MVP）

- **json 反序列化失败 / 缺必填字段 / 类型不对 / 数值越界 / schemaVersion 非 1**：
  - 打 error 日志 + 指标（如 `malformed_message_total` / `schema_version_mismatch_total`）
  - **MVP 建议：记录后仍 `commit` 该条 offset**（避免“毒消息”长期阻塞消费；重复投递风险较低，因无法形成有效 `eventId` 去重时也不会误派单，但会制造噪音，因此必须靠日志/指标可发现）
- **未知扩展字段**：忽略（必须容忍）

#### 5.3.3 版本演进

- 新增字段：仅允许 **向后兼容的 additive** 变更
- 破坏性变更：升级 **topic 版本**（`...v2`）或新 topic；outbox 发布器按版本路由

示例：

```json
{
  "schemaVersion": 1,
  "eventId": "123456",
  "eventType": "ORDER_CREATED_NEED_DISPATCH",
  "orderNo": "20260426xxxx",
  "cityCode": "330100",
  "productCode": "ECONOMY",
  "origin": { "lat": 30.251612, "lng": 120.141275 },
  "createdAt": "2026-04-26T14:20:00+08:00"
}
```

约定：

- **裁决以服务端 DB 为准**：消息不作为状态机裁决依据，只作为“触发一次派单尝试”的输入。
- schema 演进建议：新增字段保持向后兼容，消费者容忍未知字段。
- **反序列化失败/必填字段缺失**：本期先做错误日志 + 指标告警并跳过（避免卡住消费），后续再引入 DLQ（死信队列）承接坏消息。

> 与「手动逐条 commit」配套的落地建议：对 **malformed/契约不合格** 的消息，MVP 选择 **ack + commit**（避免 poison message 卡死），但必须靠日志/指标能发现；未来再上 DLQ 或专门 poison handler。

### 5.4 Kafka 客户端配置（已拍板，MVP）

> 说明：本期不引入多环境命名隔离、TLS/SASL；网络与认证参数按本地/测试集群默认可跑通为准。

#### Producer（order-service outbox 发布器）

- **幂等 Producer 开启**：`enable.idempotence=true`（配合 `acks=all` 为常见组合，具体以客户端默认/团队规范为准）
- 目标：降低 outbox 发布重试场景下“同一条事件被重复写入 topic”的概率（**不替代**下游 `processed_event`/order CAS 幂等）

#### Consumer（capacity-service 派单消费）

- **Consumer Group**：`capacity.order.dispatch.requested.v1`  
  - 含义清晰：服务 + topic 版本；后续若需要多环境隔离，可再改为加前缀（本期不隔离）
- **`enable.auto.offset.reset=earliest`**（已拍板）  
  - 仅在该 group 对某 partition **首次**无已提交 offset 时生效：从最早可读位置开始消费（可能读到历史积压，需配合 topic 生命周期/清理策略评估）
- **Offset 提交：手动 + 逐条**（已拍板）  
  - 语义：单条消息处理结束且得到明确结论后 `commitSync`（或等价的手动提交），避免“处理失败但已提交”的丢消息  
  - 代价：吞吐低于批量提交；MVP 更简单、行为更直观
- **`enable.auto.commit=false`**：与手动提交配套

---

## 6. 数据模型（DB 表）

### 6.1 order 库新增：`order_outbox_event`

用途：连接本地事务与 Kafka 发布，保证“订单创建成功 → 派单事件不会丢”。

`payload` 的生成方式（已拍板，取“最简单、最少依赖”）：

- **优先**：在 `create(CreateOrderBody)` 同事务中，**直接用入参**组装 outbox 的 `payload`（`schemaVersion=1`、补齐 `orderNo`/`eventId(=outbox id)`/时间戳等），**不再额外回查 `trip_order`**
- **原因**：`payload` 主要是排障/审计/发布 JSON 的原材料；**业务裁决**仍以 DB 中 `trip_order` 为准
- 若未来出现入参与落库字段不一致的极端问题：以 **落库** 为准，并视为 bug（MVP 不引入复杂补偿）

建议字段：

- `id`（PK，事件 ID，建议 bigint 自增或 UUID）
- `topic`（如 `order.dispatch.requested.v1`）
- `event_type`（`ORDER_CREATED_NEED_DISPATCH`）
- `aggregate_id`（`orderNo`）
- `payload`（JSON/TEXT）
- `status`：`PENDING` / `PROCESSING` / `PUBLISHED` / `FAILED`
- `retry_count`
- `next_retry_at`
- `created_at` / `updated_at`

索引建议：

- `idx_outbox_status_next`：(`status`, `next_retry_at`, `id`)
- （可选）`uk_outbox_event_type_agg`：(`event_type`, `aggregate_id`) 防止同一订单重复产生同类事件

### 6.2 capacity 库新增：`capacity_processed_event`

用途：消费端幂等去重（同一 `eventId` 只处理一次）。

字段建议：

- `id`（PK）
- `consumer_group`
- `event_id`
- `processed_at`

唯一键：

- UNIQUE(`consumer_group`, `event_id`)

> 实现建议采用“抢占式去重”：先插入，唯一键冲突则跳过。

---

## 7. 端到端流程（时序）

### 7.1 第一段：下单（同步）

1) `passenger-api` 对外接口收到下单请求
2) `passenger-api` 调 `order-service POST /api/v1/orders` 创建订单
3) `order-service` 在同一事务中：
   - 插入 `trip_order(status=CREATED)`
   - 插入 `order_event(ORDER_CREATED)`
   - 插入 `order_outbox_event(status=PENDING, payload=派单所需字段)`
4) 返回 `orderNo` 给 `passenger-api`，对外响应允许：
   - `status=CREATED`
   - `assignedDriver=null`

### 7.2 outbox 发布（异步）

1) outbox 发布器扫描 `order_outbox_event`（`status=PENDING` 且 `next_retry_at<=now`）
2) 通过 DB CAS 抢占式领取：
   - `PENDING -> PROCESSING`
3) 发布 Kafka：`topic=order.dispatch.requested.v1`，`key=orderNo`，`value=payload + eventId`
4) 发布成功：
   - `PROCESSING -> PUBLISHED`
5) 发布失败：
   - `PROCESSING -> PENDING/FAILED`（按策略）
   - `retry_count++`，设置 `next_retry_at`（退避 + 抖动）

### 7.3 capacity 消费（异步派单）

收到消息后：

1) **幂等占坑**：插入 `capacity_processed_event(consumer_group,eventId)`
   - 冲突：直接 ack 跳过
2) 找司机：
   - 调用 capacity 内部“最近司机/3km/可接单”逻辑（建议支持 topN 候选）
3) 无司机：
   - 记日志/指标（no_driver）
   - ack 结束（不重试）
4) 有司机：
   - 调 `order-service assign`（CAS：`CREATED -> ASSIGNED`）
   - 成功后调 `order-service openOffer`（`ASSIGNED -> PENDING_DRIVER_CONFIRM`）
   - （可选）写 `pending-order-index`
   - ack 结束

与既有调度的关系：

- “无司机”由你们已有迟滞匹配（司机上线触发 + 定时兜底）长期推进
- “确认窗口超时/改派/下一轮 offer”仍由既有扫描器推进

---

## 8. 幂等与冲突处理（capacity 消费端决策）

### 8.1 幂等模型

两层门闩：

1) **事件级幂等（capacity）**：`capacity_processed_event` 占坑去重
2) **业务级幂等（order）**：`assign` 使用 `where status=CREATED` 的 CAS；重复调用不会把订单推进错

### 8.2 Top3 候选司机（一次消费内的备用重选，建议启用）

> 目的：提高“首派成功率”，避免因并发/信息滞后导致的 `assign` 冲突直接把订单留在 `CREATED`。  
> 约束：本方案不依赖 Kafka 重投；在**同一次消费处理**里做有限次重选即可。

#### 能解决的典型问题

- capacity 查询时司机看起来可接单，但在调用 `order.assign` 时已接了别的订单 → order 返回 **409**（司机服务中）
- 多笔订单并发都选中同一司机 → 只有一笔能成功，其余会 CAS/冲突

#### 实现建议

- capacity “找司机”能力从返回 1 个最近司机升级为返回 **Top3 候选**（按距离升序）。
- 消费端按候选顺序尝试 `assign -> openOffer`：
  - 推荐最多尝试 **2~3 次**（Top3 全部尝试也可，但不建议无限循环）

#### 接口形态建议（capacity 内部）

- 新增/改造一个内部方法（或 API）返回候选列表：
  - 输入：`cityCode, productCode, originLat, originLng, limit=3`
  - 输出：候选司机列表（含 `driverId/carId/companyId/carNo` 等派单所需字段）

> 注意：如果目前只有 `nearest-driver`（单个），建议先在内部实现一个 `nearest-drivers(limit)`，再逐步对外暴露。

#### 消费端伪代码（规范化）

```text
onMessage(event):
  if !tryInsertProcessed(event.eventId):   // unique(consumer_group,event_id)
     ack; return

  candidates = findNearestDrivers(limit=3, cityCode, productCode, originLatLng)
  if candidates.empty:
     metric.no_driver++
     ack; return

  for cand in candidates:
     r1 = order.assign(orderNo, cand.driverId, cand.carId, cand.companyId)
     if r1.success:
        r2 = order.openOffer(orderNo, offerSeconds=30)
        if r2.success:
           metric.success++
           ack; return
        else:
           // openOffer 失败：通常意味着状态已变或下游异常
           // MVP：直接结束（交给既有调度兜底）；进阶：可对下游异常做一次短暂重试
           metric.open_offer_fail++
           ack; return

     if r1.code == 409:                    // 司机服务中/不可派
        metric.driver_in_service++
        continue                           // 换下一个候选

     if r1.isConflictOrInvalidState:       // 订单已非 CREATED / 已取消 / 已被派 / 不存在
        metric.order_conflict++
        ack; return                        // 不再尝试其他司机（订单已无意义）

     if r1.isTransientError:               // 502/timeout
        metric.downstream_error++
        ack; return                        // MVP：结束；后续可进入本地补偿队列

  // Top3 都没成功
  metric.all_candidates_failed++
  ack; return
```

#### 关键分支约定（便于统一实现）

- `order.assign` 返回 “订单不存在/状态不允许/已取消/已被派”：
  - **直接结束**（不再换司机），因为订单已不处于可派状态
- `order.assign` 返回 409（司机服务中）：
  - **继续尝试下一个候选**（这是 topN 的最大价值点）
- `order.openOffer` 失败：
  - MVP：直接结束，由既有调度体系兜底（避免复杂补偿）

### 8.2 结果分类（建议）

- **无司机**：结束（不重试）
- **order.assign 冲突/状态不允许/订单不存在**：结束（消息延迟/并发正常现象）
- **order.assign 409（司机服务中）**：建议在同一次消费内换候选 1–2 次；仍失败则结束（交给迟滞匹配/后续调度）
- **下游短暂故障（timeout/502）**：
  - MVP：结束并记录错误，后续靠迟滞匹配/调度兜底
  - 进阶：落入短延迟补偿队列（非 Kafka 重投）

> 本方案的“成功”定义是：**完成一次派单尝试并得出明确结论**，而非必须派单成功。

---

## 9. 重试策略

### 9.1 outbox 发布重试（必须）

- 仅对“发布 Kafka”失败做重试
- 建议退避：1s、5s、30s、2min、10min（上限）+ 抖动
- 超过上限可标记 `FAILED` 并告警

---

## 9.1a Outbox 发布器：CAS 领取 + 超时回收（实现规格）

> 目的：即使在多实例部署、XXL-JOB 故障转移、人工补跑等场景下，也能保证 outbox 记录“最多被一个发布器实例同时处理”，并支持崩溃后恢复推进。  
> 约束：**领取/标记与发送必须解耦**，避免持有 DB 锁期间做 Kafka 网络 IO。

### 状态定义（`order_outbox_event.status`）

- `PENDING`：待发布（可领取）
- `PROCESSING`：已被某实例领取，正在发布中
- `PUBLISHED`：已确认发送成功并标记完成
- `FAILED`：超过最大重试/人工介入（可选）

建议新增字段：

- `processing_at`：进入 `PROCESSING` 的时间（用于超时回收）
- `processing_by`：领取者标识（如 hostname/instanceId，便于排障）
- `last_error`：最近一次发布失败原因（可选）

### 批量领取（claim）规则

1) 查询候选（不加锁即可）：
   - 条件：`status = PENDING AND next_retry_at <= now()`
   - 排序：`ORDER BY id ASC`
   - 限制：`LIMIT batchSize`
2) 对每条记录做 CAS 领取（关键 SQL 语义）：
   - `UPDATE ... SET status=PROCESSING, processing_at=now(), processing_by=?, updated_at=now() WHERE id=? AND status=PENDING`
   - **仅当 affectedRows=1 时，才算领取成功**；否则跳过（被其他实例抢占或状态已变）

> 说明：XXL-JOB 能减少多实例并发触发，但不应假设“永不并发”；CAS 领取是最后保险。

### 发布（send）规则

对每条领取成功的 outbox 记录：

- 构造 Kafka 消息：
  - topic：`order.dispatch.requested.v1`
  - key：`orderNo`（从 `aggregate_id` 或 payload 解析）
  - value：payload + `eventId`（即 outbox `id`）
- 发送 Kafka（异步回调或同步等待均可，MVP 推荐同步等待结果更直观）
- 发送成功后标记完成：
  - `UPDATE ... SET status=PUBLISHED, updated_at=now() WHERE id=? AND status=PROCESSING`

### 失败处理与退避（retry backoff）

当 Kafka 发送失败（异常/超时）：

- 计算下次重试时间 `next_retry_at`（指数退避 + 抖动）
- `retry_count = retry_count + 1`
- 状态回退到 `PENDING`（便于后续再次领取）：
  - `UPDATE ... SET status=PENDING, retry_count=retry_count+1, next_retry_at=?, last_error=?, updated_at=now() WHERE id=? AND status=PROCESSING`
- 若 `retry_count` 超过上限：
  - 标记 `FAILED` 并告警（或继续留在 PENDING 但 next_retry_at 拉长，按团队偏好）

### 超时回收（reclaim）规则（处理实例崩溃/卡死）

周期性回收“疑似卡死”的 `PROCESSING` 记录：

- 条件：`status=PROCESSING AND processing_at < now() - processingTimeout`（例如 2~5 分钟）
- 回收动作（推荐 CAS）：
  - `UPDATE ... SET status=PENDING, next_retry_at=now(), updated_at=now() WHERE id=? AND status=PROCESSING`

> 结果：可能导致重复发送（例如实际已发送成功但未能标记 PUBLISHED）。  
> 本方案接受“可能重复投递”，并要求下游用 **capacity 事件级去重（processed_event）** + **order 状态机 CAS** 抵御副作用。

### 并发与一致性补充说明

- “发送成功但 DB 标记 PUBLISHED 失败”会导致后续重复发送：这是 outbox 常见现实，本方案通过消费端幂等兜底。
- 若未来希望进一步降低重复率，可在 Kafka producer 开启幂等 producer、或在 value 中带 `eventId` 方便排障与去重。

### 9.2 capacity 消费重试（按本方案：不依赖 Kafka）

“无司机”不重试；其它错误默认不重投 Kafka。

可选增强（本期不做，留作后续优化）：

- **capacity 本地短延迟补偿**（zset/表 + job/XXL-JOB）处理“下游短暂故障”（timeout/502）。  
  目标：不让 Kafka 分区堆积前提下，提高首派成功率；建议只做少量次数（如 1~3 次）与短时间窗口（如 10~60s）。

---

## 10. API 语义变更（对外）

### 10.1 新增接口：`create`（推荐）

决策：**对外新增 `create` 接口**，用于“两段式”下单；保留既有 `createAndAssign` 作为兼容接口（可标记 deprecated，后续下线）。

接口路径（已决策）：

- `POST /app/api/v1/orders/create`

两段式落地后，对外 `create` 的“下单成功”含义变为：

- **订单已创建**（`orderNo` 已生成并落库）
- 派单异步推进，可能短时间内仍为 `CREATED`

需要前端/产品配合：

- 页面展示“派单中”
- 继续通过订单详情/轮询观察状态推进

### 10.2 响应建议（MVP）

`create` 接口返回最小字段即可：

- `orderNo`
- `status`：通常为 `CREATED`
- `assignedDriver`：允许为 `null`

> 说明：如果保留路线/估价返回，可继续携带 `route/estimate`，但不再承诺“本次响应一定已派到司机”。

### 10.3 兼容策略

- `createAndAssign`：
  - 短期内保留，便于旧前端不改即可继续跑（但建议尽快迁移到 `create`）
  - 服务端可逐步把其内部实现改为“调用 `create` + 返回 CREATED”（即行为对齐），避免维护两套逻辑
- 文档/前端联调以 `create` 为主；`createAndAssign` 进入淘汰计划

---

## 11. 可观测性与排障（最小必备）

### 11.1 关键指标

- order outbox：
  - `outbox_pending_count`
  - `outbox_oldest_pending_age_seconds`
  - `outbox_publish_success_total / failure_total`
- capacity consumer：
  - `dispatch_event_consumed_total`
  - `dispatch_attempt_success_total`
  - `dispatch_attempt_no_driver_total`
  - `dispatch_attempt_order_conflict_total`
  - `dispatch_attempt_driver_in_service_total`
  - `dispatch_attempt_downstream_error_total`
- 端到端延迟：
  - `order_created_to_offer_opened_ms`（可通过日志/事件时间戳统计）

### 11.2 排障路径

给定 `orderNo`：

1) 看 `trip_order` 当前状态
2) 看 `order_event` 流水是否有 `ORDER_ASSIGNED` / `OFFER_OPENED`（若有）
3) 看 outbox 事件是否 `PUBLISHED`
4) 看 capacity 是否处理过 `eventId`（processed_event 是否存在）

---

## 12. 迁移/发布计划（建议）

1) 先在 order-service 增加 outbox 表与发布器（不改 passenger-api），灰度验证发布链路
2) capacity 接入 consumer + processed_event 表，先只打印日志不执行派单（shadow）
3) 切换 passenger-api：下单只 create，不再同步 assign/openOffer
4) 开启 capacity 真正派单
5) 观察 outbox 堆积、派单延迟与冲突分类；候选 **Top3 + 同一次消费内重选** 已作为默认策略（`8.2`），若线上仍出现大量 409/冲突，再调参（半径、候选数、过滤规则等）

---

## 13. 拍板状态（与本文一致）

- **outbox 多实例发布**：**CAS 领取 + PROCESSING 超时回收**（`9.1a`）
- **capacity 选司机候选**：**Top3 + 同一次消费内重选**（`8.2`）
- **消息契约 v1**：`5.3.1` 字段表 + `schemaVersion=1`；MVP 不做 DLQ，坏消息 **日志 + 指标**（`5.3.2`）
- **对外 API**：新增 `POST /app/api/v1/orders/create`；`createAndAssign` 兼容淘汰（`10`）
- **本地短延迟补偿**：本期不做（`9.2`）
- **Kafka 客户端配置**：`earliest`、手动逐条 commit、固定 consumer group、幂等 producer（`5.4`）
- 仍可能随环境变化的运维项（如 topic 生命周期/分区数/压测与 lag 监控等）见 `14.0`

---

## 14. 实现层注意点与上线前检查清单

### 14.0 Kafka 与运维环境（MVP 注意点，非业务代码但上线必查）

- **Topic 与分区**：`order.dispatch.requested.v1` 需提前创建；分区数与吞吐目标/并发消费者数量匹配（MVP 可先保守，再按压测调参）
- **Retention/清理策略**：`earliest` 首次消费可能读到历史；需明确 topic **保留时间**与是否允许**手工清空测试 topic**（避免联调期历史消息误伤）
- **消费 Lag 监控**：为 `group.id=capacity.order.dispatch.requested.v1` 配 lag 告警
- **集群可用性与 ACL**：本期先不强调 TLS/SASL，但需记录 **bootstrap 地址、认证策略未来补齐点**
- **发布器与 Job**：outbox 发布器若用 XXL-JOB/定时触发，需避免同任务重入并发（结合 `9.1a` 的 CAS 领取）

### 14.1 时间与 JSON 表达（避免联调/统计“看起来随机失败”）

- `createdAt` 必须是 **可解析的 ISO-8601**，并**带时区**或团队统一为 UTC；消息时间仅用于排障/统计，不用于业务裁决
- 业务时间裁决一律以 `order-service` DB（如 `created_at/offer_expires_at`）为准
- 经纬度 JSON 中统一使用 **number**（不要 `"lat":"30.25"` 与 number 混用，否则契约校验/客户端实现会分裂）
- `eventId` 在消息 JSON 中统一用 **string**；`capacity_processed_event.event_id` 建议与之一致，避免大整数在 JSON/JS 侧精度问题

### 14.2 Kafka 消费语义与“坏消息处理”的副作用（需要配套监控）

- 当前方案为 **至少一次**语义：重试/重启会导致重复消费；因此必须依赖：
  - capacity：`processed_event` 事件级去重
  - order：状态机 CAS
- 对 **malformed/契约不合格** 的消息，MVP 选择 **打日志/指标后仍 commit** 以避免“毒消息”卡死消费（见 `5.3.2` 与文末说明）  
  - 这会带来一个副作用：**坏消息被吞掉且不再触发处理**，因此必须有 `malformed_message_total` 等告警
- `enable.auto.offset.reset=earliest`：新 consumer group 首次无 offset 时，可能**补消费历史消息**；需要结合 topic 生命周期/堆积情况评估“首次发布冲击”（必要时在消费者侧加防护，但非本期必需）

### 14.3 Outbox 发布重复与指标（别把它当成“业务问题”）

- 典型情况：Kafka 已发送成功，但 DB 将 outbox 标记为 `PUBLISHED` 失败/超时  
  结果：可能重复发同一事件（同 `eventId`）  
- 这属于 outbox 常见现象：靠 capacity `processed_event` 去重即可；建议监控 `outbox_publish_duplicate_suspect_total` 或从日志抽样观测重复 `eventId`

### 14.4 配置一致性（确认窗单一真源）

- `offerSeconds` 与 `openDriverOffer` 默认窗口必须 **单一真源**：当前仓库默认 **30s**（`capacity.dispatch.driver-offer-seconds`、`passenger-api` `app.order.driver-offer-seconds`、`order` `OpenDriverOfferBody` 等对齐）；避免各服务各自默认导致「8s / 10s / 30s」并存。
- `radiusMeters` 与 `candidateLimit` 作为 capacity 的算法参数，放配置文件（不放进 Kafka 消息，避免“消息里藏配置”难治理）

### 14.5 Top3 与算法一致性

- 单条 `nearest` 与 Top3 列表应使用**同一套过滤/排序规则**（例如同样应用 3km 半径、在线、可接单等），否则会出现“列表第二个可用但第一个不可用”的不可解释行为

### 14.6 发布顺序与双通道（最容易线上事故）

- 严格避免同时存在“同步 `assign/openOffer`”与“异步消费派单”长期并行：这会把 `CREATED/ASSIGNED` 冲突率显著拉高
- 推荐灰度：先跑通 outbox+发布+shadow consumer，再切 `create`，最后打开真正派单（见 `12`）

### 14.7 上线前检查清单（建议打印成 PR checklist）

- **Schema**：`5.3.1` 字段/类型/范围校验在 consumer 落地；unknown 字段可忽略
- **幂等**：
  - outbox 发布器 CAS + reclaim（`9.1a`）
  - `processed_event` 插入冲突即跳过
  - `order.assign` 以 DB CAS 为最终门闩
- **配置**：`offerSeconds` 与仓库默认 **30s** 对齐（单点来源）；`radiusMeters=3000`；`candidateLimit=3`
- **观测**：`malformed_message_total`、outbox 堆积、最老 PENDING 年龄、Kafka consumer lag
- **回归**：`POST /app/api/v1/orders/create` 返回可 `CREATED` + 异步派单可推进到 `PENDING_DRIVER_CONFIRM`（以 DB 状态为准）
