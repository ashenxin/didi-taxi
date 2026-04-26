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
| 司机登录/WS | 《司机端_登录注册设计.md》《司机端_登录注册接口文档.md》《司机端_WebSocket与实时协议入门.md》 |
| 网关 | 《网关服务_设计.md》《网关服务_技术.md》 |
| 后台（汇总） | 《后台管理系统_权限清单与鉴权设计.md》《后台管理系统_权限与接口文档.md》《后台管理系统_订单管理_设计.md》《后台管理系统_订单管理_接口文档.md》《后台管理系统_运力配置_设计.md》《后台管理系统_运力配置_接口文档.md》 |
| 订单技术 | 《订单服务幂等与并发方案说明.md》《订单与派单_两段式Outbox与Kafka_技术方案.md》 |

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
- TODO 中曾写的「全量关键时间线、`cancelBy`、事件 payload 折叠」等排障增强：**非本期硬性范围**。

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

1. **前端**：登录/菜单/计价/运力页与现 BFF **对齐验收**（若在独立仓库，单列一轮联调）。  
2. **按需：审计**：在业务/审计表中补齐结构化日志（操作者 id、资源 id、动作、结果、时间等）。  
3. **按需**：修改密码接口、其它 `GET .../{id}` 详情类接口（详见《运力配置_设计》「按需再补」）。

---

## 二、全仓文档待办与未实现功能清单（按领域）

本节来自已删除旧文档《文档待办与未实现功能清单.md》的合并内容。其中“后台管理摘录”已被上一节吸收并以更新状态为准，本节不再重复列出后台条目。

> 整理日期：**2026-04-15**（原文档日期）

### 2.1 乘客端 / 订单 / 运力（核心业务）

#### 2.1.1 已实现（与文档对齐要点，便于区分「未做」）

- 一步下单、`createAndAssign`、订单详情轮询、`cancel`；**待派单超时系统取消**（`order.dispatch.wait-timeout-seconds`，默认 180s）+ **offer 超时** 扫描（`OfferTimeoutScheduler`）。
- **司机池 GEO + 最近司机派单**（capacity）、**迟滞匹配**：司机上线触发（`LateDispatchMatchService.tryMatchAfterDriverOnline`）+ **定时兜底**（`LateDispatchScheduler` → `listPendingDispatchAll` / order `GET .../internal/pending-dispatch-all` → GEO 最近候选 `assign` + `openDriverOffer`）、**指派后订单池索引**（`pending-order-index`）。配置：`capacity.dispatch.late-match-scan-interval-ms`、`late-match-batch-limit`。
- **下线 / 登出删司机池**：听单开关 `online:false` 与 **`driver-api` `POST .../auth/logout`** 均 Feign 调用运力 `POST .../drivers/{id}/online`（`online:false`），`DriverStatusService` 落库 `monitor_status=0` 并在事务提交后 **`DriverGeoRedisPool.remove`**；司机 `cityCode` 为空时无法按 key 移除会打 warn。
- 状态机含 **`PENDING_DRIVER_CONFIRM`**、`openDriverOffer`、`accept` 多笔待确认互斥系统取消等（以 `TripOrderWriteService` 为准）。
- **改派 / 下一轮 offer**：`OfferTimeoutScheduler` 将超时单打回 `ASSIGNED` 后，**capacity** `OfferRescheduleScheduler`（默认 5s）拉取 order `GET .../internal/assigned-awaiting-reschedule`，按 **`offer_round` 与 `same-driver-max-offer-rounds`** 决定同司机再开窗口或 **`POST .../internal/reassign`** + `openDriverOffer`；Redis 订单池 `removePending` / `addPending`。

#### 2.1.2 未实现或仅部分落地（待办）

| 优先级 | 项 | 说明（文档出处） |
|--------|-----|------------------|
| **中** | **乘客端 WebSocket** | 《最小闭环》**§1.2、§6**：目标形态为 WS；当前 **HTTP 轮询**。 |
| **中** | **接驾 ETA（司机位置 → 上车点）** | 《最小闭环》附录/表格：当前 ETA 多为占位或路线时长；**matrix + 实时坐标** 未接。 |
| **中** | **`passenger_display_code` / 改派中展示** | 《Redis与听单下线策略》**§7.3**：建议字段与 `RE_DISPATCHING` 等；**表/接口未要求本期必做**。 |
| **中** | **两段式异步指派 + Outbox + Kafka** | 《`乘客司机端_最小闭环接口调用文档.md`》**§3.0**；专项方案《`订单与派单_两段式Outbox与Kafka_技术方案.md`》；当前 **同步** `createAndAssign` 为主，后续切 `POST /app/api/v1/orders/create`。 |
| **中** | **幂等键 `Idempotency-Key`** | 《最小闭环》**§3.0** 建议；见《订单服务幂等与并发方案说明》。 |
| **低** | **轮询顺带触发匹配（限频）** | 《Redis》**§6.2**：可选；**默认不做**。 |

### 2.2 Redis 司机池 / 订单池

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **中** | **司机心跳续 GEO + TTL 策略** | 文档要求听单期 **心跳更新坐标**、防僵尸；当前以 **上线写 GEO + key TTL** 为主，**无**独立心跳接口周期。 |
| **低** | **订单池与 DB 对账** | 《Redis》**§8.6**：可选对账；**未强制实现**。 |
| **低** | **乘客 GET 顺带迟滞匹配** | 《Redis》流程图 **§8.5**：标注「可选须限频」；**未作为默认实现**。 |

### 2.3 司机端：会话 / WebSocket / Presence

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **高** | **业务 WebSocket + 派单推送** | 《司机端_上线听单与接单设计》**§5**、《WebSocket与实时协议入门》：握手鉴权、心跳、离线写库、消息 envelope；**仓库无默认 WS 业务通道**（H5 可有 demo 连接触发，非完整业务）。 |
| **高** | **Presence 与断线裁决** | 同上与《登录注册设计》**§12**：与 HTTP 登出分期协同。 |
| **中** | **登出后「待确认 offer 不再推送」** | 文档目标；依赖 WS + 订单规则，**分期**。 |
| **中** | **网关 WebSocket 路由与多实例** | 《网关服务_技术》**§3.1**：规划；需路由、升级、Sticky/PubSub 等。 |

### 2.4 网关

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **低** | **OAuth2 Resource Server 标准栈** | 《网关服务_技术》：可选与当前 jjwt 并存方案。 |
| **中** | **WS 转发与生产级配置** | 与司机端 WS 同期。 |

### 2.5 地图 / 计费 / 其他

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **低** | **城市 geocode 映射扩展** | 《最小闭环》**§1.3**：仅部分 `cityCode` 映射。 |
| **低** | **幂等方案全量落地** | 《订单服务幂等与并发方案说明》：与业务接口逐一对齐。 |

---

## 三、修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-25 | 合并两份清单为单一入口《TODO与差距总览.md》，并以后端更新更晚的后台核对状态为准（换队 POST / 车辆列表 / reviewedBy 已完成）。 |

