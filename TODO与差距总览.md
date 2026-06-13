# TODO 与差距总览（单一入口）

本文件合并自：

- 《后台管理系统_TODO核对与待办.md》（已删除，内容已合并入本文）
- 《文档待办与未实现功能清单.md》（已删除，内容已合并入本文）

后续请**只维护本文件**；旧入口文档已删除。

> 合并日期：**2026-04-25**  
> 说明：若同一事项在不同文档里状态不一致，以**代码最新实现**与**更新时间更晚**的条目为准。

---

## 〇、文档索引

| 领域 | 主要文档 |
|------|----------|
| 乘客/司机闭环 | 《乘客司机端_最小闭环接口调用文档.md》 |
| Redis 司机池/订单池 | 《乘客司机端_Redis与听单下线策略.md》 |
| 司机上线/接单/调度 | 《司机端_上线听单与接单设计.md》 |
| 司机登录/WS | 《司机端_登录注册_PRD.md》《司机端_登录注册_TECH.md》《司机端_登录注册_API.md》《司机端_WebSocket与实时协议入门.md》 |
| 网关 | 《网关服务_设计.md》《网关服务_技术.md》 |
| 后台（汇总） | 《后台管理系统_权限清单与鉴权设计.md》《后台管理系统_权限与接口文档.md》《后台管理系统_订单管理_PRD.md》《后台管理系统_订单管理_TECH.md》《后台管理系统_订单管理_API.md》《后台管理系统_运力配置_PRD.md》《后台管理系统_运力配置_TECH.md》《后台管理系统_运力配置_API.md》 |
| 订单技术 | 《订单与派单_订单服务幂等与并发方案说明.md》《订单与派单_两段式Outbox与Kafka_技术方案.md》 |

---

## 一、后台管理系统（实现核对快照）

本节来自已删除旧文档《后台管理系统_TODO核对与待办.md》的合并内容，用于记录后台相关“已覆盖/差距/排期建议”。

> 核对日期：**2026-04-24**（以当时提交为准；本地未提交改动请自行复核）

### 1.1 订单管理

#### 1.1.1 已覆盖（与设计与接口文档一致）

| 项 | 说明 |
|----|------|
| BFF | `GET /admin/api/v1/orders`、`GET /admin/api/v1/orders/{orderNo}` |
| 筛选 | 订单号、手机、省市、状态、时间区间、分页；**省/市与 JWT 域合并**（`AdminDataScope`） |
| `orderNo` vs `phone` | 有 `orderNo` 时不走手机换 `passengerId`；手机无乘客 → **空列表** |
| 列表防 N+1 | 列表行清空 `passengerId`/`passengerPhone`；详情再取乘客手机 |
| 下游 | `order` 分页/详情/events；`passenger` by-phone / by-id |
| 事件顺序 | `order-service` 侧按 `occurredAt`（及 id）升序；与前端升序展示约定一致 |

#### 1.1.2 仍为 MVP+ / 未做（与设计文档「非目标」一致）

- 退款对账、人工改单/派单、导出、手机号脱敏、结构化审计等。
- **订单运维排障入口**：已有 order/capacity 内部诊断接口，但后台还没有统一页面或 admin-api 聚合入口。建议作为近期高优先级开发项，详见 §2.6。
- **订单时间线增强**：`order_event` 已有基础流水，但后台/联调视角仍需更清晰地折叠 payload、展示中文状态与关键原因，详见 §2.6。

### 1.2 运力与换队

#### 1.2.1 已覆盖

| 项 | 说明 |
|----|------|
| BFF 路径 | `/admin/api/v1/capacity/companies`、`.../drivers`、`.../drivers/{id}/cars`；换队 **`/admin/api/v1/capacity/team-change-requests`**（含 `pending-count`、列表、详情、approve/reject） |
| capacity 直连 | 公司分页、司机分页（含 **`provinceCode`/`cityCode`**、**`companyId`**、`online` 等）、**`GET /api/v1/drivers/{id}`**、司机车辆分页；管理端换队 **`/api/v1/admin/driver-team-change-requests*`** |
| 数据域 | 公司/司机/司机车辆/换队列表与待审数/详情/审核均走 **`AdminDataScope`**；换队 VO 含 **`driverCityCode`**；详见《权限与接口文档》**§4.7** |
| 计价 BFF | **`/admin/api/v1/pricing/fare-rules`** CRUD（`AdminPricingController` + `CalculateClient`）；规则读写同样受省/市域约束 |
| 鉴权（后端） | **`POST /admin/api/v1/auth/login`**、`GET /admin/api/v1/auth/me`、`GET /admin/api/v1/auth/menus`；JWT + `passenger` `sys_*` 校验（见权限接口文档） |
| 独立车队管理页 | **不做**（以公司列表表达组织维度），与《运力配置_设计》一致 |

#### 1.2.2 仍存在的差距 / 技术债

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **低** | **修改密码等 auth 扩展** | 权限文档中的 **`PUT .../auth/password`** 等是否在 BFF 接满，可与《权限与接口文档》逐条对照。 |
| **低** | **审核结构化审计日志** | 设计文档建议字段；若仅业务/控制台日志，记为 **可观测性待加强**。 |
| **低** | **通用前端组件** | SearchForm / DataTable 等抽象：页面内联实现为主则视为 **技术债**（前端仓核对）。 |

> 已完成项（从旧清单迁移并在本节不再作为差距项重复列出）：  
> - ✅ 换队申请提交（POST）闭环  
> - ✅ 独立车辆列表 BFF  
> - ✅ 审核 `reviewedBy` 使用登录用户

### 1.3 全站鉴权与数据域（摘要）

- **RBAC、JWT、`token_version`、菜单树**：以 `passenger` `sys_*` + `admin-api` 为准（权限两份文档）。
- **订单 / 计价 / 运力 / 换队**：列表与写操作 **`AdminDataScope`** 已接；**403**（越界筛选）与 **404**（跨域资源掩蔽）语义见权限接口文档 §4.7。
- **后台员工（如 SUPER / 省管）CRUD**：`admin-api` **`/admin/api/v1/system/admin-users`** 等与 passenger 内部接口联动，细节见权限接口文档。

### 1.4 建议排期（按收益）

1. **后台/运维排障页或 admin-api 诊断聚合接口**：承接 order/capacity 内部诊断能力，按 `orderNo` 一站式排查派单链路。
2. **前端**：登录/菜单/计价/运力页与现 BFF **对齐验收**（若在独立仓库，单列一轮联调）。
3. **按需：审计**：在业务/审计表中补齐结构化日志（操作者 id、资源 id、动作、结果、时间等）。
4. **按需**：修改密码接口、其它 `GET .../{id}` 详情类接口（详见《运力配置_设计》「按需再补」）。

---

## 二、全仓文档待办与未实现功能清单（按领域）

本节来自已删除旧文档《文档待办与未实现功能清单.md》的合并内容。其中“后台管理摘录”已被上一节吸收并以更新状态为准，本节不再重复列出后台条目。

> 整理日期：**2026-04-15**（原文档日期）

### 2.1 乘客端 / 订单 / 运力（核心业务）

#### 2.1.1 已实现（与文档对齐要点，便于区分「未做」）

- 一步下单、`createAndAssign`、订单详情、`cancel`；乘客端稳态通过 WS `ORDER_CHANGED` 触发拉取一次 HTTP 详情，WS 不可用时才降级轮询；**等待态累计 180s 系统取消**覆盖 `CREATED / ASSIGNED / PENDING_DRIVER_CONFIRM`（XXL `orderCreatedDispatchTimeoutScan`，建议 **30s**）+ **offer 30s 超时释放指派**（XXL `orderOfferTimeoutScan`，建议 **5s**，释放回 `CREATED` 并重派）。
- **司机池 GEO + 司机级 Presence + 最近司机派单**（capacity）、**迟滞匹配**：司机上线/心跳更新 GEO 与 `tx:driver:presence:{cityCode}`；匹配过滤超时 Presence；XXL `capacityDriverPresenceCleanup` 清理超时司机并下线。迟滞匹配仍由司机入池触发 + `capacityLateDispatchScan` 定时兜底。
- **下线 / 登出删司机池**：听单开关 `online:false` 与 **`driver-api` `POST .../auth/logout`** 均 Feign 调用运力 `POST .../drivers/{id}/online`（`online:false`），`DriverStatusService` 落库 `monitor_status=0` 并在事务提交后 **`DriverGeoRedisPool.remove`**；司机 `cityCode` 为空时无法按 key 移除会打 warn。
- **司机登出批量释放/释单**：`driver-api` 登出 **先** 对 **`listAssignedToDriver`**（**`ASSIGNED` / `PENDING_DRIVER_CONFIRM`**）逐单 **`reject`**，`reasonCode` **`DRIVER_LOGOUT`**（与手动拒单同链路，通常 **`CREATED + 重派`**）；随后对司机 **`ACCEPTED` 且未到达** 订单复用到达前取消链路释单，回到 **`CREATED`** 并重派；再下线、**`driver:tv` INCR**。详见《`司机端_登录注册_API.md`》§7。
- 状态机含 **`PENDING_DRIVER_CONFIRM`**、`openDriverOffer`、`accept` 多笔待确认互斥系统取消等（以 `TripOrderWriteService` 为准）；**确认窗时长默认 30s**（`capacity.dispatch.driver-offer-seconds` 等与 order/passenger-api 对齐）。
- **改派 / 下一轮 offer**：新主路径由 `orderOfferTimeoutScan` 将超时单释放回 `CREATED` 并再次投递派单 Outbox；**capacity** `capacityOfferRescheduleScan`（默认 5s）保留为历史 `ASSIGNED` 待改派兜底，拉取 order `GET .../internal/assigned-awaiting-reschedule` 后推进下一轮 offer/改派。
- **司机-乘客隔离匹配（30 分钟）**：司机拒单/到达前取消后写 Redis 键 `tx:dispatch:block:dp:{driverId}:{passengerId}`（TTL 30m）；capacity 派单（Kafka 首派 + 迟滞匹配）与 order `assigned` 列表均跳过该组合。确认窗超时不写隔离键，下一轮仍可再次派给该司机。
- **乘客重派中可见性**：`passenger-api` 订单详情新增 `reDispatching`；当状态为 `CREATED` 且事件流包含 `ORDER_DRIVER_REJECTED` / `ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE` / `ORDER_OFFER_TIMED_OUT` 时置 `true`，用于前端展示“正在重新派单”。
- **司机动作通知乘客**：`driver-api` 在接单、拒单、到达前取消、到达、开始、完单成功后，查订单拿 `passengerId` 并调用 `passenger-api` 内部 `POST /app/internal/v1/orders/changed`；`PassengerWsNotifyService` 向在线乘客推 `ORDER_CHANGED`。

#### 2.1.2 未实现或仅部分落地（待办）

| 优先级 | 项 | 说明（文档出处） |
|--------|-----|------------------|
| **不开发（本阶段）** | **乘客 WS 多实例广播** | 单机内存会话 + `ORDER_CHANGED` 已支持；当前项目按单实例/本地联调验收，**不开发 Redis Pub/Sub 跨实例广播**；相关设计仅作为未来扩展资料保留。 |
| **暂缓** | **接驾 ETA（司机位置 → 上车点）** | 当前暂不继续接入高德地图服务；ETA 仍记录为后续体验项。若未来恢复，可基于司机心跳/GEO 位置 + map matrix 或驾车规划计算。 |
| **低** | **`passenger_display_code` 字段体系化** | 当前已通过详情 `reDispatching` 满足乘客端“重派中”展示；如需统一多端枚举仍可后续补标准 display_code。 |
| **已完成（主路径 + 基础诊断）/ 中（运维增强）** | **两段式异步指派 + Outbox + Kafka** | `POST /app/api/v1/orders` 已切换为两段式主路径：`passenger-api` 只做 geocode/route/estimate + 创建订单，`order-service` 同事务写 `order_outbox_event`，`orderOutboxPublish` 投 Kafka，`capacity-service` 消费后 `assign + openOffer`。已补 outbox `FAILED` 上限、手动 retry、order 侧 `dispatch-trace` 与 capacity 消费结果落库诊断。后续增强：后台/运维排障入口、DLQ、指标告警与生产参数。 |
| **已完成（下单）/ 中（扩展）** | **幂等键 `Idempotency-Key`** | 乘客下单 `POST /app/api/v1/orders` 与 `/orders/create` 已要求 Header 并透传 order-service；order 侧 `order_idempotent_record` 已覆盖 `CREATE_ORDER`，同 key 同请求体返回同一 `orderNo`，同 key 不同请求体返回 409。取消、接单、拒单、到达前取消、完单等其它写接口后续再扩展。 |
| **低** | **轮询顺带触发匹配（限频）** | 《Redis》**§6.2**：可选；**默认不做**。 |
| **已完成** | **司机 `ACCEPTED` 登出自动释单** | 待接指派登出时 **`reject(DRIVER_LOGOUT)`** → **`CREATED + 重派`**；司机 **`ACCEPTED` 且未到达** 登出自动 **释放改派 / 释单**，复用司机到达前取消链路 **`ACCEPTED → CREATED`**，不生成乘客侧 **`CANCELLED`** 终态。 |

### 2.2 Redis 司机池 / 订单池

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **已完成（单实例）** | **司机心跳续 GEO + Presence 过期策略** | 司机 H5 听单期间约 15s 调用 `POST /driver/api/v1/drivers/{driverId}/heartbeat`；capacity 使用按城市 Presence ZSET 保存司机级最后心跳，匹配过滤超时司机，XXL `capacityDriverPresenceCleanup` 清理并下线；城市 GEO key 不再使用整体 TTL。 |
| **低** | **订单池与 DB 对账** | 《Redis》**§8.6**：可选对账；**未强制实现**。 |
| **低** | **乘客 GET 顺带迟滞匹配** | 《Redis》流程图 **§8.5**：标注「可选须限频」；**未作为默认实现**。 |

### 2.3 司机端：会话 / WebSocket / Presence

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **已完成（单实例）** | **业务 WebSocket + 派单推送（替代高频轮询）** | `driver-api` 已支持 `/driver/ws/v1/stream`、`ws-token(audit=2)` 握手、`PING/PONG`、建连/变更推 `ASSIGNED_LIST`、接单/拒单/取消后强制刷新；司机 H5 现有逻辑在 WS 已连时不做 assigned 高频轮询，HTTP 仅手动/关键操作/断链兜底。 |
| **已完成（单实例）** | **Presence 与断线裁决** | WS 正常关闭、心跳超时会调用 `DriverBffService.setOnline(false)`，复用 capacity 下线链路落库并清理司机池；同司机新 WS 连接会顶旧连接且不误触发下线。已补 `driver-api` 单元测试。 |
| **低** | **登出后「待确认 offer 不再推送」** | HTTP 登出已 **批量拒指派**，列表不再含待确认单；WS 单连接与下线裁决已收口。本阶段不再追加多实例推送一致性建设。 |
| **不开发（本阶段）** | **乘客/司机 WS 多实例能力** | 本阶段只验收单实例内存 session + 网关 Upgrade 转发；**不开发 Sticky / Redis Pub/Sub / MQ 广播**。多实例资料保留在《网关服务_技术》《乘客端与司机端_WebSocket_对比.md》中，不列入近期开发计划。 |

### 2.4 网关

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **低** | **OAuth2 Resource Server 标准栈** | 《网关服务_技术》：可选与当前 jjwt 并存方案。 |
| **中** | **WS 转发与生产级配置** | **`/driver/**` / `/app/**` WebSocket Upgrade**；参见 **§3.1**。乘客已定经网关 **`/app/ws/**`**。 |

### 2.5 地图 / 计费 / 其他

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **低** | **城市 geocode 映射扩展** | 《最小闭环》**§1.3**：仅部分 `cityCode` 映射。 |
| **低** | **幂等方案全量落地** | 《订单服务幂等与并发方案说明》：与业务接口逐一对齐。 |

### 2.6 近期候选开发计划（暂不接入高德地图）

> 2026-06-13 讨论结论：暂不继续做高德地图 / 接驾 ETA，本阶段优先选择不依赖外部地图服务、能提升联调效率和系统稳定性的任务。

| 建议优先级 | 开发点 | 目标 | 主要范围 | 验收口径 |
|------------|--------|------|----------|----------|
| **P0** | **后台/运维排障页或 admin-api 诊断聚合接口** | 把 order/capacity 已有诊断能力变成一个按 `orderNo` 查询的统一入口，减少手工 curl 和跨库排查。 | admin-api 聚合 `order-service` `dispatch-trace` / outbox 查询与 `capacity-service` 消费结果；可选后台页面展示 outbox 状态、capacity 结果、卡点建议；支持 `FAILED` outbox 手动 retry。 | 输入 `orderNo` 能看到订单状态、outbox 状态、Kafka 消费结果、是否 `NO_DRIVER` / `FAILED` / `MALFORMED`，并能对 `FAILED` outbox 发起重试。 |
| **P1** | **订单时间线增强** | 让订单详情和排障视角更可读，减少直接看 `order_event.payload` 的成本。 | 统一事件 DTO；展示 `CREATED（待派单/重新派单）`、`PENDING_DRIVER_CONFIRM（待司机确认）`、`ACCEPTED（司机已接单）` 等中文状态；折叠司机拒单、确认窗超时、司机登出释单、系统取消原因。 | `GET /orders/{orderNo}/events` 或后台订单详情能返回排序稳定、状态中文清晰、原因字段明确的时间线。 |
| **P1** | **DLQ / 坏消息处理** | 对 Kafka `INVALID` / `MALFORMED` 派单消息提供可查询、可审计的死信记录，避免只靠日志。 | 新增死信表或死信 topic；记录原始 payload、topic/partition/offset、错误原因、首次/最近出现时间；提供查询接口；重放能力可后置。 | 发送非法 JSON 或缺字段消息后，consumer ack 不阻塞分区，同时死信记录可通过接口查询。 |
| **P2** | **写接口幂等扩展** | 将 `Idempotency-Key` 从乘客下单扩展到其它关键写动作，降低重复点击、超时重试和网关重放导致的副作用。 | 优先乘客取消、司机接单、司机拒单、司机到达前取消、完单；结合状态机 CAS 设计 actionType 与 request_hash；不改变订单权威状态机。 | 同 key 同请求重复调用返回同一业务结果；同 key 不同请求返回 409；状态已经推进时不产生重复事件或重复 Outbox。 |

---

## 三、修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-25 | 合并两份清单为单一入口《TODO与差距总览.md》，并以后端更新更晚的后台核对状态为准（换队 POST / 车辆列表 / reviewedBy 已完成）。 |
| 2026-04-29 | 更新乘客司机闭环状态：补记“30 分钟隔离匹配”“reDispatching 已实现”，并将 WebSocket+Presence 提升为下一阶段最高优先级。 |
| 2026-04-30 | **乘客 WS 联调收口**：网关 **`GET /app/ws/`** JWT 白名单、`passenger-api` WS、H5 Demo；TODO **§2.1.2** 乘客 WS 条目更新为「已实现骨架/联调」（后续已在 2026-06-11 明确多实例广播本阶段不开发）。 |
| 2026-06-03 | **司机 WS 单实例主路径收口**：`driver-api` WS 握手鉴权、`ASSIGNED_LIST` 推送、`PING/PONG`、心跳超时/关闭下线、同司机新连接顶旧连接已落地并通过 `mvn -pl driver-api test`；乘客/司机 WS 多实例能力本阶段不开发。 |
| 2026-06-11 | **WS 多实例能力降级为不开发项**：乘客/司机 WS 保持单实例主路径验收，网关仅要求 Upgrade 转发；Sticky、Redis Pub/Sub、MQ 广播不列入近期开发计划。 |
| 2026-06-03 | **下单 Idempotency-Key 落地**：乘客下单两个入口强制 Header，`passenger-api` 透传至 `order-service`，`order_idempotent_record` 覆盖 `CREATE_ORDER` 请求级幂等；其它写接口幂等仍后续。 |
| 2026-06-03 | **两段式异步指派主路径落地**：`POST /app/api/v1/orders` 不再同步 assign/openOffer，创建订单后由 Outbox + Kafka + capacity consumer 异步推进派单；`mvn -pl passenger-api test`、`mvn -pl order test` 已通过。 |
| 2026-06-05 | **司机级 Presence 与位置心跳落地**：新增司机心跳接口、Presence ZSET、匹配新鲜度过滤和 XXL 过期清理；修复城市 GEO key 整体 TTL 无法独立淘汰失联司机的问题。 |
| 2026-06-12 | **Outbox/Kafka 基础诊断与失败兜底**：order outbox 发布失败达到上限转 `FAILED` 并支持手动 retry；capacity `capacity_processed_event` 扩展消费结果字段，可按 `eventId` / `orderNo` 查询 `SUCCESS`、`NO_DRIVER`、`FAILED`、`INVALID`、`MALFORMED`。 |
| 2026-06-13 | **近期开发计划调整**：暂缓高德地图/接驾 ETA；新增 §2.6 四个候选开发点：后台/运维排障页、订单时间线增强、DLQ / 坏消息处理、写接口幂等扩展。 |
