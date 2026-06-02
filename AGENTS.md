# AGENTS.md

本文档给后续协作者和 AI coding agent 快速建立项目上下文。它不是替代详细 PRD/API/TECH 文档，而是项目地图：先读这里，再按链接进入专项文档。

## 项目定位

`didi-taxi` 是一个仿滴滴出行后端项目，采用 Java 21、Spring Boot 3.3.5、Spring Cloud 2023.0.5、Maven 多模块组织。当前核心目标是支撑乘客下单、订单派发、司机听单接单、后台管理、计价、地图路线、网关鉴权与 WebSocket 通知等能力。

主要设计原则：

- 网关是浏览器/H5 的统一入口，负责 CORS、JWT 验签和可信 `X-User-Id` 注入。
- BFF 只做端侧聚合编排，不做订单状态裁决。
- `order-service` 是订单状态机和事件流水的权威来源。
- `capacity-service` 维护司机在线/听单能力、Redis GEO 司机池和调度匹配。
- Redis 用作索引、缓存和推送辅助，不替代 DB 权威状态。
- 关键写操作必须走状态条件更新/CAS，避免并发接单、取消、改派导致状态错乱。

## 模块地图

| 模块 | 默认端口 | 职责 |
|---|---:|---|
| `gateway` | 18080 | Spring Cloud Gateway；路由 `/admin/**`、`/app/**`、`/driver/**`；JWT、CORS、WS 握手白名单。 |
| `admin-api` | 8099 | 后台管理 BFF；登录、菜单、订单管理、运力管理、计价管理、换队审核。 |
| `passenger-api` | 8100 | 乘客端 BFF；登录、下单、详情、取消、退出登录、乘客 WebSocket 小票与通知。 |
| `driver-api` | 8101 | 司机端 BFF；登录注册、上线听单、待接单、接/拒/取消/到达/开始/完成、司机 WebSocket。 |
| `order` | 8093 | 订单服务；订单主表、状态机、事件流水、超时取消、offer 超时、接单互斥。 |
| `capacity` | 8090 | 运力/调度服务；司机、公司、车辆、Redis GEO 司机池、迟滞匹配、换队申请。 |
| `calculate` | 8091 | 计价服务；预估价、计价规则 CRUD。 |
| `map` | 8094 | 地图服务；路线、地理编码/逆地理编码等高德相关能力。 |
| `passenger` | 8092 | 乘客核心服务；乘客账号、后台 `sys_*` 权限相关内部能力。 |
| `xxl-job-admin` | 8080 | XXL-JOB 调度中心；访问路径 `/xxl-job-admin`，供 `order`、`capacity` 执行器注册和手动触发任务。 |

根 `pom.xml` 管理 Spring Boot、Spring Cloud、Java 21、XXL-JOB 与 Gateway 兼容版本。注意当前固定 `spring-cloud-starter-gateway` / `spring-cloud-gateway-server` 为 `4.1.5`，用于避免 Boot 3.3.5 与 Gateway 4.1.6 的 Spring Web 方法兼容问题。

## 核心链路

### 乘客下单与派单闭环

1. 乘客经 `gateway` 调用 `passenger-api` 的 `/app/api/v1/orders`。
2. `passenger-api` 聚合 `map`、`calculate`、`order`、`capacity`。
3. `order` 创建订单并维护状态机；`capacity` 基于 Redis GEO 找附近司机并指派。
4. 司机通过 `driver-api` 查看待接单并执行接单、拒单、到达、开始、完成等动作。
5. 订单状态推进以 `order` 为准，BFF 不应绕过 `order` 直接裁决。

关键状态/规则：

- 等待总时长默认 180 秒，配置见 `order.dispatch.wait-timeout-seconds`。
- 司机确认窗默认 30 秒，BFF、order、capacity 需保持一致。
- 拒单、到达前取消、offer 超时会触发收回/改派。
- 司机与乘客拒单/取消后存在 30 分钟隔离匹配键：`tx:dispatch:block:dp:{driverId}:{passengerId}`。
- 乘客详情中的 `reDispatching` 用于展示“正在重新派单”。

### WebSocket

- 乘客 WS：`/app/ws/v1/stream`，先通过 `/app/api/v1/auth/ws-token` 换短期小票，再在 Query `token=` 中握手。
- 乘客端稳态采用事件驱动：`ORDER_CHANGED` 只携带 `orderNo + seq`，前端收到后再拉一次 `GET /app/api/v1/orders/{orderNo}`；HTTP 详情仍为展示权威，WS 不可用时才降级轮询。
- 司机动作触发乘客刷新链路：`driver-api` 在接单、拒单、到达前取消、到达、开始、完单成功后，查 `order-service` 获取 `passengerId`，调用 `passenger-api` 内部接口 `POST /app/internal/v1/orders/changed`，再由 `PassengerWsNotifyService` 推 `ORDER_CHANGED`。
- 司机 WS：`/driver/ws/**` 同类设计，司机端待确认指派应 WS 优先；HTTP assigned 仅用于首次加载、上线/关键操作后对账和手动刷新，避免依赖高频短轮询。
- 网关对 `GET /app/ws/**`、`GET /driver/ws/**` 握手放行，实际身份由 BFF 校验小票。
- 生产不要直连 BFF 端口；本地排障可以直连。
- 多实例下乘客侧设计方向是 Redis Pub/Sub 广播，各节点有会话再下行。

### 鉴权与身份

- 前端统一经 `gateway` 访问。
- JWT 按端隔离：
  - `/admin/**`：`aud=admin-bff`，密钥 `JWT_SECRET_ADMIN`。
  - `/app/**`：`aud=app-bff`，密钥 `JWT_SECRET_APP`。
  - `/driver/**`：`aud=driver-bff`，密钥 `JWT_SECRET_DRIVER`。
- 网关验签后删除客户端伪造的 `X-User-Id`，再注入可信 `X-User-Id`。
- BFF 中的业务接口读取 `X-User-Id` 作为当前用户；直连 BFF 联调时需手动补头或关闭/绕过相关检查。
- 本地临时无 token 联调可设置 `GATEWAY_JWT_REQUIRE_AUTH=false`，生产必须为 `true`。

## 接口入口速查

所有接口统一返回类似：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

### 网关路径

| 端 | 网关前缀 | 下游 |
|---|---|---|
| 后台 | `/admin/**` | `admin-api` |
| 乘客 | `/app/**` | `passenger-api` |
| 司机 | `/driver/**` | `driver-api` |

### 乘客端常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/app/api/v1/auth/sms/send` | 发送短信验证码 |
| `POST` | `/app/api/v1/auth/login-sms` | 短信登录 |
| `POST` | `/app/api/v1/auth/login-password` | 密码登录 |
| `POST` | `/app/api/v1/auth/logout` | 退出登录；到达前可能代取消 |
| `POST` | `/app/api/v1/auth/ws-token` | 换取乘客 WS 小票 |
| `POST` | `/app/api/v1/orders` | 当前 H5/MVP 权威一步下单入口 |
| `POST` | `/app/api/v1/orders/create` | 历史兼容或后续两段式/Outbox 演进入口，不作为当前 H5 推荐入口 |
| `GET` | `/app/api/v1/orders/{orderNo}` | 订单详情 |
| `POST` | `/app/api/v1/orders/{orderNo}/cancel` | 乘客取消 |

### 司机端常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/driver/api/v1/auth/sms/send` | 发送短信验证码 |
| `POST` | `/driver/api/v1/auth/register-sms` | 短信注册 |
| `POST` | `/driver/api/v1/auth/register-password` | 密码注册 |
| `POST` | `/driver/api/v1/auth/login-sms` | 短信登录 |
| `POST` | `/driver/api/v1/auth/login-password` | 密码登录 |
| `POST` | `/driver/api/v1/auth/logout` | 退出登录；会下线听单并批量拒绝待确认指派 |
| `POST` | `/driver/api/v1/auth/ws-token` | 换取司机 WS 小票 |
| `POST` | `/driver/api/v1/drivers/{driverId}/online` | 上线/下线听单 |
| `GET` | `/driver/api/v1/orders/assigned` | 当前司机待接单列表 |
| `GET` | `/driver/api/v1/orders/{orderNo}` | 订单详情 |
| `POST` | `/driver/api/v1/orders/{orderNo}/accept` | 接单 |
| `POST` | `/driver/api/v1/orders/{orderNo}/reject` | 拒单 |
| `POST` | `/driver/api/v1/orders/{orderNo}/cancel` | 到达前取消 |
| `POST` | `/driver/api/v1/orders/{orderNo}/arrive` | 到达上车点 |
| `POST` | `/driver/api/v1/orders/{orderNo}/start` | 开始行程 |
| `POST` | `/driver/api/v1/orders/{orderNo}/finish` | 完成行程 |

### 后台常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/admin/api/v1/auth/login` | 后台登录 |
| `GET` | `/admin/api/v1/auth/me` | 当前后台用户 |
| `GET` | `/admin/api/v1/auth/menus` | 菜单树 |
| `GET` | `/admin/api/v1/orders` | 订单列表 |
| `GET` | `/admin/api/v1/orders/{orderNo}` | 订单详情 |
| `GET` | `/admin/api/v1/capacity/companies` | 公司列表 |
| `POST` | `/admin/api/v1/capacity/companies` | 新增公司 |
| `GET` | `/admin/api/v1/capacity/drivers` | 司机列表 |
| `GET` | `/admin/api/v1/capacity/cars` | 车辆列表 |
| `GET` | `/admin/api/v1/capacity/team-change-requests` | 换队申请列表 |
| `POST` | `/admin/api/v1/capacity/team-change-requests/{id}/approve` | 审核通过 |
| `POST` | `/admin/api/v1/capacity/team-change-requests/{id}/reject` | 审核驳回 |
| `GET` | `/admin/api/v1/pricing/fare-rules` | 计价规则列表 |
| `POST` | `/admin/api/v1/pricing/fare-rules` | 创建计价规则 |

## 运行与验证

### 常用命令

```bash
mvn test
mvn -pl passenger-api test
mvn -pl order test
mvn -pl gateway spring-boot:run
mvn -pl passenger-api spring-boot:run
```

### 本地依赖

按模块功能不同，可能需要：

- MySQL：各服务的业务库，配置在对应 `application.yml`。
- Redis：乘客/司机 token version、司机 GEO 池、WS/调度辅助键。
- Kafka：order outbox / 派单异步化相关，当前部分链路仍以同步 `createAndAssign` 为主。
- XXL-JOB：调度中心已纳入 `xxl-job-admin` 子模块，默认 `http://127.0.0.1:8080/xxl-job-admin`；注意不要和 Spring 定时任务重复跑同一逻辑。
- 高德地图 Key：map 服务调用外部地图能力时需要。

### 日志

各模块多写到本仓对应 `logs` 目录，例如：

- `order/logs/order-service`
- `passenger-api/logs/passenger-api`
- `driver-api/logs/driver-api`
- `capacity/logs`

排查订单问题时优先按 `orderNo` 串联：

1. `order` 主表状态与 `order_event`。
2. `capacity` 派单/司机池日志。
3. BFF 请求日志。
4. 网关 401/403/502、CORS、WS 101 握手状态。

## 开发约定

- 修改接口前先查对应 API 文档，避免文档与实现分叉。
- 新增或修改状态推进时，优先在 `order` 增加权威逻辑，BFF 只编排调用。
- 涉及订单状态并发的写操作，必须使用状态条件更新或等价 CAS。
- 涉及网关鉴权时，同时核对 `aud`、密钥、白名单、`X-User-Id` 注入和 CORS。
- 涉及 WebSocket 时，同时核对网关 Upgrade 转发、握手白名单、BFF 小票校验、心跳和多实例策略。
- 涉及司机池/调度时，记住 Redis 是索引；最终合法性仍回到 order/capacity DB 状态。
- 文档名大量使用中文，搜索时优先用 `rg`，不要只依赖 IDE 侧边栏。

## 重要文档索引

### 总览与待办

- `README.md`
- `TODO与差距总览.md`
- `功能测试清单.md`

### 乘客/司机闭环

- `第一期MVP_乘客派单司机闭环_PRD.md`
- `第一期MVP_乘客派单司机闭环_TECH.md`
- `第一期MVP_乘客派单司机闭环_API.md`
- `第一期MVP_乘客派单司机闭环_TEST.md`
- `乘客司机端_最小闭环接口调用文档.md`
- `乘客司机端_Redis与听单下线策略.md`

### 订单与派单

- `订单服务幂等与并发方案说明.md`
- `订单与派单_两段式Outbox与Kafka_技术方案.md`
- `司机端_上线听单与接单设计.md`

### 登录与 WebSocket

- `乘客端_登录_PRD.md`
- `乘客端_登录_TECH.md`
- `乘客端_登录_API.md`
- `司机端_登录注册_PRD.md`
- `司机端_登录注册_TECH.md`
- `司机端_登录注册_API.md`
- `司机端_WebSocket与实时协议入门.md`
- `乘客端与司机端_WebSocket_对比.md`

### 网关

- `网关服务_设计.md`
- `网关服务_技术.md`

### 后台管理

- `后台管理系统_权限清单与鉴权设计.md`
- `后台管理系统_权限与接口文档.md`
- `后台管理系统_订单管理_PRD.md`
- `后台管理系统_订单管理_TECH.md`
- `后台管理系统_订单管理_API.md`
- `后台管理系统_运力配置_PRD.md`
- `后台管理系统_运力配置_TECH.md`
- `后台管理系统_运力配置_API.md`
- `后台管理系统_计价管理_PRD.md`
- `后台管理系统_计价管理_TECH.md`
- `后台管理系统_计价管理_API.md`

### 司机换队

- `司机_换队功能_PRD.md`
- `司机_换队功能_TECH.md`
- `司机_换队功能_API.md`

## 当前已知差距摘录

以 `TODO与差距总览.md` 为准，常见需要注意的后续项：

- 乘客 WS 已有骨架/联调，Redis Pub/Sub 跨实例广播仍需继续完善。
- 司机端业务 WebSocket、Presence、断线裁决仍是重要后续方向。
- 两段式异步指派、Outbox、Kafka 与 `Idempotency-Key` 仍需按专项方案推进。
- 接驾 ETA 仍需实时坐标和 matrix 能力补齐。
- 司机心跳续 GEO 与 TTL 防僵尸策略仍需加强。
- 司机登出后 `ACCEPTED` 到达前释单口径与乘客侧取消口径仍有差异。
