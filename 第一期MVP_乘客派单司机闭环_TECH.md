# 第一期 MVP：乘客派单司机闭环（TECH）

> 本文档把第一期闭环的“技术实现口径”写清楚：系统如何派单、如何改派、如何兜底取消、如何保证并发下状态不乱、以及如何排障。
>
> - **产品口径（权威）**：见 `第一期MVP_乘客派单司机闭环_PRD.md`
> - **接口契约（权威）**：后续落到 API 文档（本文先给实现与排障所需的要点）

---

## 0. 读者与目标

- **读者**：后端 / 前端（了解边界与字段来源）/ 测试（理解机制与排障）
- **目标**：
  - 明确服务边界与职责（谁推进状态，谁只读）
  - 让关键规则可落地（总等待 180s、拒单/取消/收回→改派、到达后禁止通过退出登录取消）
  - 提供统一排障路径（按 orderNo 能一路查到原因）

---

## 1. 总体架构与职责边界（第一期）

### 1.1 服务清单（按职责）

- **gateway**：统一入口；JWT 鉴权；注入 `X-User-Id`（客户端不可伪造）
- **passenger-api（BFF）**：乘客侧聚合编排（下单、详情、取消、登出等对外接口）；负责对接 map/calculate/order/capacity
- **driver-api（BFF）**：司机侧聚合编排（上线听单、指派列表、接单/拒单/取消、登出等对外接口）
- **order-service（权威）**：
  - 订单主表 `trip_order` 的**权威状态机**
  - 订单事件流水 `order_event`
  - “等待兜底取消”“offer/接单并发门闩”等裁决在此完成
- **capacity-service（运力/调度）**：
  - 司机在线/可接单状态维护
  - Redis 司机池（GEO）与调度侧“找司机/迟滞匹配/改派推进”等
- **calculate-service / map-service**：计价与路线（第一期按现有实现）

### 1.2 第一期开闭环的核心原则

- **状态机裁决以 order-service 为准**：所有写操作必须通过 order 的 CAS/约束推进，避免多处写导致“看起来系统乱了”。
- **BFF 只做编排，不做裁决**：BFF 不应凭前端倒计时/本地时钟决定“是否超时/是否可取消”。
- **Redis 是索引，不是权威**：司机池/订单池辅助提升匹配效率，最终以 DB 状态为准。
  - **司机池**：Redis GEO 索引（“哪些司机在听单、位置在哪里”）。
  - **订单池（如有）**：指派成功后的派生索引（例如 `driverId -> orderNo` 的“待接单索引”），用于加速 `listAssigned`/对账；**不是** CREATED 的“待派单权威队列”。
  - **CREATED 待派单的权威来源**：order-service DB 查询/内部接口 + capacity 侧入池触发/定时兜底的迟滞匹配（不要误以为一定存在 Redis 的“CREATED 订单池”）。

---

## 2. 数据模型与状态机（以 order-service 为权威）

### 2.1 `trip_order` 关键字段（概念层）

- `status`：订单状态（CREATED/ASSIGNED/ACCEPTED/ARRIVED/STARTED/FINISHED/CANCELLED/...）
- `driver_id`：指派司机
- `assigned_at` / `accepted_at` / `arrived_at` / `started_at` / `finished_at` / `cancelled_at`
- `cancel_by` / `cancel_reason`
- （若存在）`offer_expires_at` / `offer_round` / `last_offer_at`：用于“等待司机接单/超时收回/改派推进”

> 说明：第一期 PRD 统一了“等待态”，即使 DB 有更细状态，前端可按 PRD 做展示归并。

### 2.2 写操作的门闩：CAS 条件更新

第一期所有关键写操作均应满足：

- 更新必须带“当前状态条件”，例如：
  - `update ... where order_no=? and status=CREATED`
  - `update ... where order_no=? and status in (ASSIGNED, PENDING_DRIVER_CONFIRM) and driver_id=?`
- 目标：并发下只有一个合法推进者成功，其它返回冲突（由 BFF 转成用户可理解提示）

相关专项文档：
- `订单服务幂等与并发方案说明.md`

---

## 3. 调度与等待态：派单、改派、收回、兜底

### 3.1 “等待态”的技术含义（与 PRD 对齐）

PRD 中“等待态”包含三类内部来源：

1) **CREATED**：订单已创建但未指派司机（无候选/无司机）
2) **ASSIGNED / PENDING_DRIVER_CONFIRM**：已指派给某司机，但司机尚未确认接单（可被拒单/超时收回）
3) **释放改派/重新派单**：由拒单/取消/互斥等触发，订单回到“可再次指派”的状态（实现可为 CREATED 或 ASSIGNED 的某种组合，关键是语义一致）

前端展示不依赖这些内部差异，只需知道：**在等待**、**可取消**、**总等待 180s 到期系统取消**。

### 3.2 总体等待 180s：裁决点建议放在 order-service

PRD 口径：从“下单成功”开始累计 180 秒，到期仍未形成“司机已接单”则系统取消，且中途改派不重置。

工程落地建议（第一期）：

- **计时起点**：`created_at`
- **判定未接单**：订单状态仍处于“等待态集合”（例如 CREATED/ASSIGNED/PENDING_DRIVER_CONFIRM），且未进入 ACCEPTED
- **取消动作**：状态推进到 CANCELLED，`cancel_by=系统`，`cancel_reason` 写“暂无车辆/无人接单”语义
- **执行方式**：order-service 定时扫描（XXL-JOB/Scheduled 皆可，但多实例需避免重复执行副作用）

> 注意：当前仓库已存在 `order.dispatch.wait-timeout-seconds`（默认 180）用于 CREATED 超时取消。若要严格满足 PRD “总体等待”口径，需要把“改派中仍计入 180s”纳入扫描条件（TECH 先写口径，API/实现阶段再对齐）。

#### 3.2.1 与现有实现对齐：把“CREATED 超时取消”升级为“全等待态总体 180s 兜底”

现状（从仓库配置/代码可见）：

- 已有配置：`order.dispatch.wait-timeout-seconds=180`
- 已有扫描：主要针对 **CREATED** 状态的超时系统取消（“待派单超时”）

PRD 的差异点：

- PRD 要求“**总体等待** 180 秒”：即使中途派到司机、又因拒单/取消/超时收回进入改派，**也仍计入同一个 180 秒**，到期仍未接单则系统取消。

建议落地方案（第一期可选其一，优先推荐 A）：

- **方案 A（推荐：一套兜底扫描覆盖所有等待态）**
  - **兜底时钟**：固定使用 `created_at` 作为唯一真源（不会被改派重置）
  - **等待态集合**：定义一组“仍在等车/等接单”的状态集合 `WAITING_SET`
    - 最小集合建议：`CREATED`、`ASSIGNED`、`PENDING_DRIVER_CONFIRM`（若存在）
    - 关键约束：集合中不得包含 `ACCEPTED`、`ARRIVED`、`STARTED`、`FINISHED`、`CANCELLED`
  - **扫描条件**：
    - `status in WAITING_SET`
    - `created_at < now - 180s`
    - 且仍未接单（可直接由 `status` 排除 ACCEPTED；如需更稳，可加 `accepted_at is null`）
  - **动作**：系统取消
    - `status -> CANCELLED`
    - `cancel_by=系统`
    - `cancel_reason` 统一为“暂无车辆/无人接单”语义（PRD 口径）
  - **幂等与并发**：用 CAS 更新（`where status in WAITING_SET`）保证多次扫描不会重复取消。

- **方案 B（过渡：保留 CREATED 超时取消 + 新增“非 CREATED 等待态兜底”扫描）**
  - 保留现有 CREATED 扫描逻辑不动
  - 新增一个兜底扫描，仅处理 `ASSIGNED/PENDING_DRIVER_CONFIRM/...` 且 `created_at` 超时的订单
  - 该方案便于小步上线，但要保证“两个扫描任务”不会写出冲突结果（仍需 CAS）。

#### 3.2.2 取消原因与事件记录（与 PRD 对齐）

PRD 要求“3 分钟到期系统取消”的对外语义是：**附近暂无可用车辆 / 当前暂无司机接单**。

工程建议：

- 系统兜底取消的 `cancel_reason` 用统一文案（或统一 code + 文案），避免与“CREATED 待派单超时”出现两套不一致文案。
- 事件流水（`order_event`）建议记录一个明确的原因码，例如 `DISPATCH_OR_ACCEPT_TIMEOUT`（命名可定），用于排障统计。

#### 3.2.3 扫描周期与“可接受抖动”

由于兜底取消依赖定时扫描，PRD 允许“180s + 扫描间隔级别的抖动”。

工程建议：

- 扫描间隔使用现有 `order.dispatch.timeout-scan-interval-ms`（如 30s）
- 对外不承诺精确到秒，只保证“不会无限等待”

### 3.3 拒单/取消/超时未接 → 收回并改派（capacity + order 配合）

**统一目标**：司机侧让出该单占用后，订单回到“可再次找司机”的状态，乘客展示“正在重新派单”。

触发来源：

- **司机拒单**（`driver-api` → `order-service`）：已实现；**`ASSIGNED` / `PENDING_DRIVER_CONFIRM` → `CREATED`**，清空指派并再次写入 **`ORDER_CREATED_NEED_DISPATCH`** Outbox，由 capacity/Kafka/迟滞扫描推进下一轮派单
- **司机超时未接**（order 或 capacity 扫描）：收回该单，进入改派
- **司机已接单后取消（到达前）**（`driver-api` → `order-service` **`/driver/cancel`**）：已实现；**`ACCEPTED` → `CREATED`**，同样再投递派单 Outbox
- **互斥收敛**：司机接成一单后，其它单释放改派

落地要点：

- “收回/释放”是写操作，必须经 order 状态机 CAS（拒单/到达前取消与上述一致）
- capacity 可负责“再找司机 + 再指派 + 再打开接单窗口”（迟滞匹配/改派推进）
- passenger-api 在订单详情增加 `reDispatching` 字段：当订单当前为 `CREATED` 且事件流出现 `ORDER_DRIVER_REJECTED` 或 `ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE` 时置为 `true`；前端据此展示“正在为您重新派单”

相关专项文档：
- `乘客司机端_Redis与听单下线策略.md`

---

## 4. Redis 司机池与迟滞匹配（capacity 为主）

### 4.1 司机池（Redis GEO）职责

- 表达“当前可参与匹配的听单司机位置”
- 支持按上车点做“半径内最近司机”检索（如 3km）
- 仍需与 DB 运力状态（可接单/在线等）共同裁决

### 4.2 迟滞匹配（无司机时的后续推进）

第一期建议采用“入池触发为主 + 定时兜底”为主路径：

- **入池触发**：司机上线听单/位置更新时，尝试匹配一批待派单订单
- **定时兜底**：周期性扫描待派单订单，避免漏网/事件丢失

优势：
- 负载与乘客轮询解耦
- 并发冲突由 order CAS 门闩兜底

### 4.3 司机-乘客 30 分钟隔离匹配（已实现）

规则目标：司机拒绝某单（或已接单到达前取消）后，30 分钟内不再把该司机与该乘客重新匹配，避免“刚拒绝又派回”。

实现口径：

- **写入时机（order）**：`rejectByDriver`、`driverCancelBeforeArrive` 成功后写 Redis 键  
  `tx:dispatch:block:dp:{driverId}:{passengerId}`，TTL=30 分钟（配置项：`order.dispatch.driver-passenger-block-minutes`）。
- **生效点 1（capacity 派单）**：Kafka 首派与迟滞匹配（司机上线触发/定时扫描）在候选司机循环中检查该键，命中则跳过当前候选。
- **生效点 2（司机刷新指派单）**：`order-service` 的 `listAssignedToDriver` 返回前按同键过滤，隔离期内该乘客订单不出现在司机待接列表。
- **权威口径**：该键仅作为派单约束索引，不改变订单状态机权威（仍以 order DB + CAS 为准）。

---

## 5. 退出登录（Logout）与订单取消（到达前）

PRD 新增口径：乘客/司机退出登录，在司机到达前视为“取消本单”；到达后禁止通过退出登录取消。

工程落地建议：

- **鉴权层**：logout 使 token 立即失效（如 token_version/黑名单/Redis），保证退出后不可继续调用业务接口
- **业务层**：
  - logout 时若存在进行中订单且未到达：触发一次“取消/释放”写操作（经 order CAS）
  - 到达后 logout：仅退出登录，不做取消；对用户给明确提示

关键点：
- “是否到达”必须以 order 权威字段/状态为准（例如 ARRIVED 或 arrived_at 非空），不要用前端倒计时裁决

---

## 6. 配置项（第一期关键参数）

> 以当前仓库已有配置为基础，第一期重点关注“等待兜底”和“扫描周期”。

- `order.dispatch.wait-timeout-seconds`：CREATED 等待超时（当前默认 180）
- `order.dispatch.timeout-scan-interval-ms`：超时扫描频率（当前默认 30000）

capacity 侧（示例，具体以实现为准）：
- 迟滞匹配扫描间隔、批量大小
- 改派/再开接单窗口扫描间隔
- 匹配半径（如 3km）

---

## 7. 观测与排障（按 orderNo 的最短路径）

### 7.1 你要能回答的 5 个问题

给定 `orderNo`：

1) 订单当前 `status` 是什么？
2) 订单是否已进入 ACCEPTED（接单）？若没有，已等待多久？
3) 最近一次从“等待→改派”的原因是什么（拒单/取消/超时收回/互斥）？
4) 司机池里是否存在可匹配司机（GEO 半径内）？
5) 是否存在定时任务未运行/配置不一致导致的“该取消没取消/该改派没改派”？

### 7.2 推荐排障顺序（第一期最实用）

- **先查 order DB**：
  - `trip_order`：状态、关键时间戳、cancel_by/cancel_reason、driver_id
  - `order_event`：是否发生过 ASSIGNED/ACCEPTED/ARRIVED/CANCELLED/REASSIGNED/收回等事件
- **再查 capacity**：
  - 司机是否在线/可接单（DB）
  - Redis GEO 中司机是否存在、是否在半径内
  - 调度扫描日志：是否尝试过 assign/openOffer、是否冲突
- **最后查 BFF**：
  - 订单详情是否正常轮询展示
  - 是否因为登出导致 token 失效后无法刷新而“看起来卡住”

---

## 8. 与现有专项文档的分工（避免重复）

- **幂等与并发**：`订单服务幂等与并发方案说明.md`
- **Redis/听单/迟滞匹配**：`乘客司机端_Redis与听单下线策略.md`
- **Outbox/Kafka（二期方案）**：`订单与派单_两段式Outbox与Kafka_技术方案.md`
- **回归测试细节**：`功能测试清单.md`

