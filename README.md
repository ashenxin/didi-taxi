# 仿滴滴平台-后端

本仓库是仿滴滴出行后端，多模块 Maven 工程，使用 Java 21、Spring Boot 3.3.5、Spring Cloud 2023.0.5。前端仓库在同级目录 `../didi-taxi-front`。

## 模块与端口

| 模块 | 默认端口 | 说明 |
|---|---:|---|
| `gateway` | 18080 | 三端统一入口，负责 `/admin/**`、`/app/**`、`/driver/**` 路由、JWT、CORS、WS 握手放行。 |
| `admin-api` | 8099 | 后台管理 BFF，承接登录、菜单、订单、运力、计价、换队审核等后台接口。 |
| `passenger-api` | 8100 | 乘客端 BFF，承接登录、下单、订单、个人中心、钱包、乘客 WS。 |
| `driver-api` | 8101 | 司机端 BFF，承接司机登录注册、听单、接拒单、行程推进、换队、司机 WS。 |
| `capacity` | 8090 | 运力/调度服务，维护司机、公司/车队、车辆、Redis GEO、异步派单和换队申请。 |
| `calculate` | 8091 | 计价服务，维护预估价、计价规则、优惠券模板、用户券、用券流水。 |
| `passenger` | 8092 | 乘客核心服务，同时承载后台 `sys_*` 账号、角色、菜单、数据域等内部能力。 |
| `order` | 8093 | 订单服务，维护订单主表、订单状态机、订单事件、结算快照。 |
| `map` | 8094 | 地图服务，封装高德路线、地理编码、逆地理编码等能力。 |
| `wallet` | 8095 | 钱包服务，维护支付宝/微信免密协议、默认免密渠道、钱包支付单、mock 自动扣款。 |
| `xxl-job-admin` | 8081 | XXL-JOB 调度中心，访问路径 `/xxl-job-admin`。 |

## 本地启动

常用启动命令：

本地联调统一显式激活 `local` profile；不要依赖应用默认 profile。`dev` 仅用于需要开发级调试配置时手动替换。

```bash
mvn -pl gateway spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl passenger-api spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl driver-api spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl admin-api spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl passenger spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl order spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl calculate spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl wallet spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl map spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl xxl-job-admin spring-boot:run -Dspring-boot.run.profiles=local
```

默认/生产启动采用失败关闭策略：除 `local/dev/test` 外，gateway、admin-api、passenger-api、driver-api 均拒绝开发 JWT 密钥或不足 32 字节的密钥；gateway 还要求鉴权和 audience 校验开启、三端密钥互不相同。生产至少需要：

```text
GATEWAY_JWT_REQUIRE_AUTH=true
JWT_SECRET_ADMIN=<独立随机值，至少 32 字节>
JWT_SECRET_APP=<独立随机值，至少 32 字节>
JWT_SECRET_DRIVER=<独立随机值，至少 32 字节>
COUPON_CLAIM_IDENTITY_PHONE_HASH_SECRET=<独立随机值，至少 32 字节>
```

不再支持以单一 `JWT_SECRET` 代替三套端侧密钥。手机号领取身份摘要使用 `HMAC-SHA256`；开发值仅放在各模块 `application-local/dev.yml`。

常用测试命令：

```bash
mvn test
mvn verify
mvn -pl passenger-api test
mvn -pl order test
mvn -pl wallet test
```

`mvn verify` 会在各业务子模块生成 JaCoCo 覆盖率报告，例如
`capacity/target/site/jacoco/index.html`；同目录还会生成可供 CI 或分析工具读取的 XML、CSV 报告。
当前统一行覆盖率门槛为 1%，只用于防止 agent 或测试采集完全失效；覆盖率用于定位自动化测试盲区，
不单独代表测试质量。后续应按模块基线逐步提高门槛。

本地依赖：

- MySQL：各模块使用独立业务库，常见库包括 `capacity`、`calculate`、`order`、`passenger`、`wallet`、`xxl_job`。
- Redis：登录 token version、司机 GEO 池、听单 Presence、WS/调度辅助键。
- Kafka：订单 Outbox 与异步派单链路。
- XXL-JOB：默认 `http://127.0.0.1:8081/xxl-job-admin`，默认账号 `admin / 123456`。
- 高德地图 Key：`map` 服务调用外部地图能力时需要。

首次使用 XXL-JOB 如本地没有 `xxl_job` 库，可执行：

```bash
mysql -h127.0.0.1 -uroot < xxl-job-admin/src/main/resources/db/tables_xxl_job.sql
```

钱包二期涉及的 `wallet`、`calculate`、`order.trip_order_settlement` SQL 草案见：

- `二期功能/乘客端_个人中心_我的钱包_免密支付与优惠券_TECH.md`

## 关键边界

- 三端前端正常都访问 `gateway:18080`，不直连核心服务。
- `admin-api`、`passenger-api`、`driver-api` 是 BFF，只做端侧聚合、身份校验和编排。
- `order` 是订单状态机和订单事件的权威来源。
- `capacity` 是司机在线、听单、候选司机和派单匹配的权威来源。
- `calculate` 是基础计价规则和优惠券规则的权威来源。
- `wallet` 是免密协议和支付单的权威来源。
- `trip_order` 不继续承载支付/优惠字段，订单结算金额写入 `trip_order_settlement`。
- 优惠券会影响真实金额，开发支付、退款、对账、收入分配前必须先确认 PRD/TECH 中的金额口径。

## 文档索引

### 总览

- `AGENTS.md`
- `TODO与差距总览.md`
- 各功能回归以同名 `*_TEST.md` 为准。

### 乘客/司机闭环

- `第一期MVP_乘客派单司机闭环_PRD.md`
- `第一期MVP_乘客派单司机闭环_TECH.md`
- `第一期MVP_乘客派单司机闭环_API.md`
- `第一期MVP_乘客派单司机闭环_TEST.md`
- `乘客司机端_最小闭环接口调用文档.md`
- `乘客司机端_Redis与听单下线策略.md`

### 订单与派单

- `订单与派单_两段式Outbox与Kafka_技术方案.md`
- `订单与派单_订单服务幂等与并发方案说明.md`
- `订单与派单_TEST.md`
- `司机端_上线听单与接单设计.md`

### 登录、网关、WebSocket

- `乘客端_登录_PRD.md`
- `乘客端_登录_TECH.md`
- `乘客端_登录_API.md`
- `乘客端_登录_TEST.md`
- `司机端_登录注册_PRD.md`
- `司机端_登录注册_TECH.md`
- `司机端_登录注册_API.md`
- `司机端_登录注册_TEST.md`
- `网关服务_设计.md`
- `网关服务_技术.md`
- `网关服务_TEST.md`
- `司机端_WebSocket与实时协议入门.md`
- `乘客端与司机端_WebSocket_对比.md`

### 后台管理系统

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

### 地图

- `地图服务_TEST.md`

### 二期功能

- `二期功能/README.md`
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
- `二期功能/乘客端_券包与登录领券_PRD.md`
- `二期功能/乘客端_券包与登录领券_TECH.md`
- `二期功能/乘客端_券包与登录领券_API.md`
- `二期功能/乘客端_券包与登录领券_TEST.md`
- `二期功能/乘客端_福利签到_PRD.md`
- `二期功能/乘客端_福利签到_TECH.md`
- `二期功能/乘客端_福利签到_API.md`
- `二期功能/乘客端_福利签到_SQL.md`
- `二期功能/乘客端_福利签到_TEST.md`
- `二期功能/乘客端_福利签到_异常补偿_TECH.md`
- `二期功能/司机_换队功能_PRD.md`
- `二期功能/司机_换队功能_TECH.md`
- `二期功能/司机_换队功能_API.md`
- `二期功能/司机端_下周开发_TODO.md`
- `二期功能/车队营销优惠券_PRD.md`
- `二期功能/车队营销优惠券_TECH.md`
- `二期功能/车队营销优惠券_API.md`
- `二期功能/车队营销优惠券_SQL.md`
- `二期功能/车队营销优惠券规则_讨论稿.md`

### 完单结算 MVP

- `docs/superpowers/specs/2026-07-17-完单结算_DESIGN.md`
- `docs/superpowers/plans/2026-07-17-完单结算_PLAN.md`
- `docs/api/完单结算_API.md`
- `docs/testing/完单结算_TEST.md`
- `乘客司机端_完单结算方案讨论.md`（历史讨论与决策追溯）

本期已实现本地 mock 距离、预计时长和实际计费时长的结算闭环，并冻结计价规则；费用减优惠后支付。已提供结算查询和只接受 `channel`（`ALIPAY`/`WECHAT`）的主动支付入口；支付失败不后台定时自动重扣，未结清订单禁止新下单。真实支付宝/微信金融渠道、退款/对账以及司机金额展示、车队/运营公司固定金额或比例分成保留为后续专项。
