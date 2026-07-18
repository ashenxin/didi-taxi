# AGENTS.md

本文档给后续协作者和 AI coding agent 快速建立项目上下文。它不是替代详细 PRD/API/TECH 文档，而是项目地图：先读这里，再按链接进入专项文档。

## 项目定位

`didi-taxi` 是一个仿滴滴出行后端项目，采用 Java 21、Spring Boot 3.3.5、Spring Cloud 2023.0.5、Maven 多模块组织。当前核心目标是支撑乘客下单、订单派发、司机听单接单、后台管理、计价、钱包支付、地图路线、网关鉴权与 WebSocket 通知等能力。

主要设计原则：

- 网关是浏览器/H5 的统一入口，负责 CORS、JWT 验签和可信 `X-User-Id` 注入。
- BFF 只做端侧聚合编排，不做订单状态裁决。
- `order-service` 是订单状态机和事件流水的权威来源。
- `capacity-service` 维护司机在线/听单能力、Redis GEO 司机池和调度匹配。
- `wallet-service` 维护免密支付协议和支付单；优惠券仍归 `calculate-service`，订单结算快照归 `order-service`。
- Redis 用作索引、缓存和推送辅助，不替代 DB 权威状态。
- 关键写操作必须走状态条件更新/CAS，避免并发接单、取消、改派导致状态错乱。

## 模块地图

| 模块 | 默认端口 | 职责 |
|---|---:|---|
| `gateway` | 18080 | Spring Cloud Gateway；路由 `/admin/**`、`/app/**`、`/driver/**`；JWT、CORS、WS 握手白名单。 |
| `admin-api` | 8099 | 后台管理 BFF；登录、菜单、订单管理、运力管理、计价管理、换队审核。 |
| `passenger-api` | 8100 | 乘客端 BFF；登录、订单、设置/注销、钱包/券包、福利签到与乘客 WebSocket。 |
| `driver-api` | 8101 | 司机端 BFF；登录注册、上线听单、待接单、接/拒/取消/到达/开始/完成、司机 WebSocket。 |
| `order` | 8093 | 订单服务；订单主表、状态机、事件流水、超时取消、offer 超时、接单互斥、完单结算编排与结算快照。 |
| `capacity` | 8090 | 运力/调度服务；司机、公司、车辆、Redis GEO 司机池、迟滞匹配、换队申请。 |
| `calculate` | 8091 | 计价服务；预估价、计价规则、优惠券模板/用户券/用券流水、福利签到与积分。 |
| `wallet` | 8095 | 钱包服务；乘客支付宝/微信免密协议、默认免密渠道、钱包支付单、mock 自动扣款。 |
| `map` | 8094 | 地图服务；路线、地理编码/逆地理编码等高德相关能力。 |
| `passenger` | 8092 | 乘客核心服务；乘客账号、后台 `sys_*` 权限相关内部能力。 |
| `xxl-job-admin` | 8081 | XXL-JOB 调度中心；访问路径 `/xxl-job-admin`，供 `order`、`capacity` 执行器注册和手动触发任务。 |

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
- 乘客/司机 WS 多实例能力本阶段不开发；当前只验收单实例内存会话与网关 Upgrade 转发，Redis Pub/Sub / Sticky 仅作为未来扩展资料保留。

### 鉴权与身份

- 前端统一经 `gateway` 访问。
- JWT 按端隔离：
  - `/admin/**`：`aud=admin-bff`，密钥 `JWT_SECRET_ADMIN`。
  - `/app/**`：`aud=app-bff`，密钥 `JWT_SECRET_APP`。
  - `/driver/**`：`aud=driver-bff`，密钥 `JWT_SECRET_DRIVER`。
- 网关验签后删除客户端伪造的 `X-User-Id`，再注入可信 `X-User-Id`。
- BFF 中的业务接口读取 `X-User-Id` 作为当前用户；直连 BFF 联调时需手动补头或关闭/绕过相关检查。
- 本地临时无 token 联调可设置 `GATEWAY_JWT_REQUIRE_AUTH=false`，生产必须为 `true`。
- 只有 `local/dev/test` profile 允许开发 JWT 密钥；其它 profile（含未指定 profile）启动时会校验网关鉴权、audience、三端独立密钥及优惠券手机号 HMAC 密钥，不安全配置会直接拒绝启动。生产不得使用兼容变量 `JWT_SECRET`。

### 钱包、优惠券与结算

- 乘客端只通过 `passenger-api` 的 `/app/api/v1/wallet/**` 访问钱包能力，不直连 `wallet`、`calculate`、`order`。
- 免密支付协议和支付单在 `wallet` 库：`wallet_auto_pay_agreement`、`wallet_payment_order`。
- 优惠券在 `calculate` 库：`coupon_template`、`user_coupon`、`coupon_use_record`；钱包页面只是展示用户资产。
- 订单支付与用券后的金额快照在 `order` 库：`trip_order_settlement`，避免继续膨胀 `trip_order`。
- 完单结算 MVP 已贯通：司机完单后异步编排稳定本地 mock 距离/预计时长/实际时长、冻结计价规则、最优券锁定、mock 免密/主动支付、核销与结算快照。费用先减优惠后支付；合法优惠后的零元单直接结清，非法金额不支付。
- 已提供 `GET /app/api/v1/orders/{orderNo}/settlement` 和 `POST /app/api/v1/orders/{orderNo}/payments`；主动支付请求体只接受 `channel`（`ALIPAY`/`WECHAT`），支付失败不做后台定时自动重试，未结清订单禁止新下单。真实支付宝/微信金融渠道、退款和对账，以及司机金额展示、车队/运营公司固定金额或比例分成仍未实现。银行卡、借钱、车险只保留前端入口。
- 车队营销优惠券已拆分正式 PRD/TECH/API/SQL，产品与开发口径以 `二期功能/车队营销优惠券_*.md` 为准；讨论稿仅用于追溯决策来源。已确认 `fare_rule` 不新增字段，优惠券按 `company_id + city_code + product_code` 与计价规则并行匹配。
- 优惠券影响真实金额，后续真实扣款、结算、退款、对账前必须复核收入分配口径；当前已定的讨论口径是平台服务费按“乘客优惠后实付车费”的 5% 计算。

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
| `GET` | `/app/api/v1/orders/{orderNo}/settlement` | 查询权威结算状态与账单；未结清时可据此引导处理 |
| `POST` | `/app/api/v1/orders/{orderNo}/payments` | 主动支付；请求体仅 `channel`，需携带新的 `Idempotency-Key` |
| `GET` | `/app/api/v1/wallet/summary` | 钱包首页摘要 |
| `GET` | `/app/api/v1/wallet/auto-pay/agreements` | 查询免密协议列表 |
| `POST` | `/app/api/v1/wallet/auto-pay/agreements/sign` | 发起支付宝/微信免密签约 |
| `POST` | `/app/api/v1/wallet/auto-pay/agreements/{agreementId}/default` | 设置默认免密渠道 |
| `POST` | `/app/api/v1/wallet/auto-pay/agreements/{agreementId}/close` | 关闭免密协议 |
| `GET` | `/app/api/v1/wallet/coupons` | 查询我的优惠券 |
| `GET` | `/app/api/v1/wallet/coupons/available` | 查询某订单可用优惠券 |

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
mvn verify
mvn -pl passenger-api test
mvn -pl order test
mvn -pl gateway spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl passenger-api spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl wallet spring-boot:run -Dspring-boot.run.profiles=local
```

全仓覆盖率使用 JaCoCo。执行 `mvn verify` 后，各业务子模块报告位于
`<module>/target/site/jacoco/index.html`，XML/CSV 位于同一目录。当前统一行覆盖率门槛为 1%，
只防止覆盖率采集完全失效；后续按模块基线逐步提高。覆盖率不能替代关键业务断言和端到端验收。

本地启动统一使用 `local`（或明确使用 `dev`）profile；乘客和司机短信 mock 在默认 profile 中关闭，仅在 `local/dev` 中开启。

### 本地依赖

按模块功能不同，可能需要：

- MySQL：各服务的业务库，配置在对应 `application.yml`。
- MySQL 业务库目前包括 `capacity`、`calculate`、`order`、`passenger`、`wallet`、`xxl_job` 等；钱包二期 SQL 见 `二期功能/乘客端_个人中心_我的钱包_免密支付与优惠券_TECH.md`。
- Redis：乘客/司机 token version、司机 GEO 池、WS/调度辅助键。
- Kafka：order outbox / 派单异步化相关；乘客下单主链路已切换为创建订单后由 Outbox + Kafka + capacity consumer 异步派单。
- XXL-JOB：调度中心已纳入 `xxl-job-admin` 子模块，默认 `http://127.0.0.1:8081/xxl-job-admin`；注意不要和 Spring 定时任务重复跑同一逻辑。
- 高德地图 Key：map 服务调用外部地图能力时需要。

### 日志

各模块多写到本仓对应 `logs` 目录，例如：

- `order/logs/order-service`
- `passenger-api/logs/passenger-api`
- `driver-api/logs/driver-api`
- `capacity/logs`
- `wallet/logs` 如后续配置 logback/file path 时补充

排查订单问题时优先按 `orderNo` 串联：

1. `order` 主表状态与 `order_event`。
2. `capacity` 派单/司机池日志。
3. 涉及支付/优惠时查看 `trip_order_settlement`、`wallet_payment_order`、`user_coupon`、`coupon_use_record`。
4. BFF 请求日志。
5. 网关 401/403/502、CORS、WS 101 握手状态。

## 开发约定

- 修改接口前先查对应 API 文档，避免文档与实现分叉。
- 新增或修改状态推进时，优先在 `order` 增加权威逻辑，BFF 只编排调用。
- 涉及订单状态并发的写操作，必须使用状态条件更新或等价 CAS。
- 涉及网关鉴权时，同时核对 `aud`、密钥、白名单、`X-User-Id` 注入和 CORS。
- 涉及 WebSocket 时，同时核对网关 Upgrade 转发、握手白名单、BFF 小票校验、心跳和多实例策略。
- 涉及司机池/调度时，记住 Redis 是索引；最终合法性仍回到 order/capacity DB 状态。
- 涉及钱包、优惠券、结算时，按服务边界落库：支付授权/支付单在 `wallet`，优惠券规则和用券流水在 `calculate`，订单金额快照在 `order`。
- 涉及金额字段、优惠券、平台服务费、车队/司机收入时，必须先查 PRD/TECH 的金额口径；没有定版口径不要直接上线真实支付/结算。
- Git 提交信息统一使用中文，必要的代码标识、模块名和通用缩写可保留英文。
- 文档名大量使用中文，搜索时优先用 `rg`，不要只依赖 IDE 侧边栏。

## 重要文档索引

### 总览与待办

- `README.md`
- `TODO与差距总览.md`
- 各专项 `*_TEST.md`。

### 乘客/司机闭环

- `第一期MVP_乘客派单司机闭环_PRD.md`
- `第一期MVP_乘客派单司机闭环_TECH.md`
- `第一期MVP_乘客派单司机闭环_API.md`
- `第一期MVP_乘客派单司机闭环_TEST.md`
- `乘客司机端_最小闭环接口调用文档.md`
- `乘客司机端_Redis与听单下线策略.md`

### 订单与派单

- `订单与派单_订单服务幂等与并发方案说明.md`
- `订单与派单_两段式Outbox与Kafka_技术方案.md`
- `订单与派单_TEST.md`
- `司机端_上线听单与接单设计.md`

### 登录与 WebSocket

- `乘客端_登录_PRD.md`
- `乘客端_登录_TECH.md`
- `乘客端_登录_API.md`
- `乘客端_登录_TEST.md`
- `司机端_登录注册_PRD.md`
- `司机端_登录注册_TECH.md`
- `司机端_登录注册_API.md`
- `司机端_登录注册_TEST.md`
- `司机端_WebSocket与实时协议入门.md`
- `乘客端与司机端_WebSocket_对比.md`

### 网关

- `网关服务_设计.md`
- `网关服务_技术.md`
- `网关服务_TEST.md`

### 后台管理

- `后台管理系统_权限清单与鉴权设计.md`
- `后台管理系统_权限与接口文档.md`
- `后台管理系统_权限_TEST.md`
- `后台管理系统_订单管理_PRD.md`
- `后台管理系统_订单管理_TECH.md`
- `后台管理系统_订单管理_API.md`
- `后台管理系统_订单管理_TEST.md`
- `后台管理系统_运力配置_PRD.md`
- `后台管理系统_运力配置_TECH.md`
- `后台管理系统_运力配置_API.md`
- `后台管理系统_运力配置_TEST.md`
- `后台管理系统_计价管理_PRD.md`
- `后台管理系统_计价管理_TECH.md`
- `后台管理系统_计价管理_API.md`
- `后台管理系统_计价管理_TEST.md`

### 司机换队

- `二期功能/司机_换队功能_PRD.md`
- `二期功能/司机_换队功能_TECH.md`
- `二期功能/司机_换队功能_API.md`

### 二期个人中心与钱包

- `二期功能/乘客端_个人中心_我的订单_PRD.md`
- `二期功能/乘客端_个人中心_我的订单_TECH.md`
- `二期功能/乘客端_个人中心_我的订单_API.md`
- `二期功能/乘客端_个人中心_我的订单_TEST.md`
- `二期功能/乘客端_个人中心_设置_PRD.md`
- `二期功能/乘客端_个人中心_设置_TECH.md`
- `二期功能/乘客端_个人中心_设置_API.md`
- `二期功能/乘客端_个人中心_设置_TEST.md`
- `二期功能/乘客端_个人中心_我的钱包_免密支付与优惠券_PRD.md`
- `二期功能/乘客端_个人中心_我的钱包_免密支付与优惠券_TECH.md`
- `二期功能/乘客端_个人中心_我的钱包_免密支付与优惠券_API.md`
- `二期功能/乘客端_个人中心_我的钱包_免密支付与优惠券_TEST.md`
- `二期功能/车队营销优惠券_PRD.md`
- `二期功能/车队营销优惠券_TECH.md`
- `二期功能/车队营销优惠券_API.md`
- `二期功能/车队营销优惠券_SQL.md`
- `二期功能/车队营销优惠券规则_讨论稿.md`
- `二期功能/乘客端_券包与登录领券_{PRD,TECH,API,TEST}.md`
- `二期功能/乘客端_福利签到_{PRD,TECH,API,SQL,TEST}.md`
- `二期功能/司机端_下周开发_TODO.md`

### 完单结算 MVP

- `docs/superpowers/specs/2026-07-17-完单结算_DESIGN.md`
- `docs/superpowers/plans/2026-07-17-完单结算_PLAN.md`
- `docs/api/完单结算_API.md`
- `docs/testing/完单结算_TEST.md`
- `乘客司机端_完单结算方案讨论.md`（历史讨论与决策追溯）

## 当前已知差距摘录

以 `TODO与差距总览.md` 为准，当前需要注意的后续项：

- 乘客/司机 WS 单实例主路径已收口；Redis Pub/Sub / Sticky 跨实例广播本阶段不开发。
- 两段式异步指派、Outbox、Kafka 与下单 `Idempotency-Key` 主路径已落地；后续重点转为后台/运维排障入口、DLQ、指标告警与写接口幂等扩展。
- 接驾 ETA 仍需实时坐标和 matrix 能力补齐；当前阶段暂不继续接入高德地图服务，先保留为后续体验项。
- 司机心跳续 GEO 与司机级 Presence 防僵尸策略已落地；XXL `capacityDriverPresenceCleanup` 仍需在运行环境配置启用。
- 司机登出后 `ACCEPTED`（司机已接单）到达前自动释单口径已明确并落地：释放改派回 `CREATED`（待派单/重新派单），不是乘客侧 `CANCELLED`（已取消）终态。
- 完单结算 MVP 已落地为本地 mock 闭环；支付渠道仍是 mock。接真实支付宝/微信前还需完成第三方签约、回调验签、退款、对账和财务评审；支付失败不由后台定时自动重试。
- 车队营销优惠券后台、目标表结构、领券、锁券/核销与结算快照已经落地；司机金额展示和车队/运营公司固定金额或比例分成仍需独立设计、实现和财务评审。
- 福利签到积分已落地；Redis/MySQL 异常补偿任务仍待建设。
- 当前重点见 `TODO与差距总览.md` §2：司机端近期功能、后台派单诊断聚合、DLQ、写接口幂等扩展与真实支付闭环。
