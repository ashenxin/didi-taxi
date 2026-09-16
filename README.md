# 仿滴滴平台后端

## 文档范围

本文档是后端仓库入口，维护两类内容：

- 全仓共用信息：项目定位、本地开发入口和文档导航。
- 一期业务范围：乘客登录与下单、订单派发、司机听单与履约、网关鉴权、WebSocket、后台基础管理、基础计价和地图能力。

一期按业务范围划分，不按代码提交时间划分。后来为一期闭环补充的 Outbox、Kafka、请求幂等、并发控制和调度治理仍归一期。乘客个人中心、账号生命周期、钱包与结算、优惠券、福利签到、司机换队、司机行程看板和 AI 客服归二期，统一从[二期功能说明](二期功能/README.md)进入。

后端技术栈、模块地图、修改授权和开发行为约定见 [AGENTS.md](AGENTS.md)。

## 项目定位

`didi-taxi` 是仿滴滴出行后端，多模块 Maven 工程。当前基础技术栈为 Java 21、Spring Boot 3.5.16、Spring Cloud 2025.0.3 和 Spring Cloud Alibaba 2025.0.0.0；根 `pom.xml` 统一管理依赖与插件版本。前端仓库位于同级目录 `../didi-taxi-front`。

系统通过三端 BFF 对外提供能力：

- 管理后台经 `/admin/**` 访问 `admin-api`。
- 乘客端经 `/app/**` 访问 `passenger-api`。
- 司机端经 `/driver/**` 访问 `driver-api`。

浏览器和 H5 正常联调统一经过 `gateway:18080`，不直接访问核心服务。

## 一期业务范围

### 乘客与司机闭环

一期覆盖以下最小业务闭环：

1. 乘客通过短信或密码登录。
2. 乘客提交起终点并创建订单。
3. `order` 保存订单并通过 Outbox、Kafka 与 `capacity` 协作派单。
4. `capacity` 根据司机在线、听单、资格和 GEO 位置选择候选司机。
5. 司机确认接单后执行到达、开始和完成行程。
6. 乘客可以取消等待中订单；司机拒单、确认超时或到达前取消后，订单按规则重新派单。
7. 乘客和司机通过 WebSocket 接收变化提醒，并通过 HTTP 查询权威订单详情。

### 一期核心边界

- `gateway` 是浏览器/H5 的统一入口，负责 JWT、CORS 和可信 `X-User-Id` 注入。
- BFF 只负责端侧身份校验、聚合和编排，不直接裁决订单状态。
- `order` 是订单状态机和订单事件的权威来源。
- `capacity` 是司机在线、听单、候选司机和派单匹配的权威来源。
- Redis 只用于索引、缓存、Presence 和推送辅助，不能替代数据库权威状态。
- 订单写操作必须遵守请求幂等和状态条件更新，避免重复写入与并发状态覆盖。
- `ORDER_CHANGED` 只用于提示客户端重新拉取详情，HTTP 订单详情仍是展示权威。

具体状态、超时、幂等键和接口契约以对应 TECH、API 和 TEST 文档为准，不在 README 中重复维护。

## 本地开发入口

后端服务通常由用户通过 IDEA 运行配置启动。是否允许操作服务进程以 [AGENTS.md](AGENTS.md) 为准。

本地联调显式使用 `local` profile。除 `xxl-job-admin` 外，业务服务通过 Nacos 加载本地运行配置并完成服务注册；首次准备环境请先阅读 [Nacos 本地配置运行手册](docs/runbooks/capacity-service-Nacos本地配置运行手册.md)。

常用验证命令：

```bash
mvn test
mvn verify
mvn -pl passenger-api test
mvn -pl order test
```

常用依赖包括 Nacos、MySQL、Redis、Kafka、XXL-JOB 和高德地图 Key。数据库脚本的归属、执行顺序和回填要求统一从 [SQL 说明](docs/sql/README.md)进入。

`mvn verify` 会在各业务模块的 `target/site/jacoco/` 下生成 JaCoCo 报告。覆盖率只用于发现测试盲区，不能替代关键业务断言和端到端验收。

## 一期文档导航

### MVP 闭环

- [一期 MVP PRD](第一期MVP_乘客派单司机闭环_PRD.md)
- [一期 MVP TECH](第一期MVP_乘客派单司机闭环_TECH.md)
- [一期 MVP API](第一期MVP_乘客派单司机闭环_API.md)
- [一期 MVP TEST](第一期MVP_乘客派单司机闭环_TEST.md)
- [乘客司机端最小闭环接口调用文档](乘客司机端_最小闭环接口调用文档.md)

### 订单与派单

- [订单服务幂等与并发方案](订单与派单_订单服务幂等与并发方案说明.md)
- [两段式 Outbox 与 Kafka 技术方案](订单与派单_两段式Outbox与Kafka_技术方案.md)
- [订单与派单测试说明](订单与派单_TEST.md)
- [司机上线听单与接单设计](司机端_上线听单与接单设计.md)
- [Redis 与听单下线策略](乘客司机端_Redis与听单下线策略.md)

### 登录、网关与实时通知

- [乘客端登录 PRD](乘客端_登录_PRD.md) / [TECH](乘客端_登录_TECH.md) / [API](乘客端_登录_API.md) / [TEST](乘客端_登录_TEST.md)
- [司机端登录注册 PRD](司机端_登录注册_PRD.md) / [TECH](司机端_登录注册_TECH.md) / [API](司机端_登录注册_API.md) / [TEST](司机端_登录注册_TEST.md)
- [网关服务设计](网关服务_设计.md) / [技术说明](网关服务_技术.md) / [测试说明](网关服务_TEST.md)
- [司机端 WebSocket 与实时协议](司机端_WebSocket与实时协议入门.md)
- [乘客端与司机端 WebSocket 对比](乘客端与司机端_WebSocket_对比.md)

### 后台管理与地图

- [后台权限设计](后台管理系统_权限清单与鉴权设计.md) / [接口文档](后台管理系统_权限与接口文档.md) / [测试说明](后台管理系统_权限_TEST.md)
- [订单管理 PRD](后台管理系统_订单管理_PRD.md) / [TECH](后台管理系统_订单管理_TECH.md) / [API](后台管理系统_订单管理_API.md) / [TEST](后台管理系统_订单管理_TEST.md)
- [运力配置 PRD](后台管理系统_运力配置_PRD.md) / [TECH](后台管理系统_运力配置_TECH.md) / [API](后台管理系统_运力配置_API.md) / [TEST](后台管理系统_运力配置_TEST.md)
- [计价管理 PRD](后台管理系统_计价管理_PRD.md) / [TECH](后台管理系统_计价管理_TECH.md) / [API](后台管理系统_计价管理_API.md) / [TEST](后台管理系统_计价管理_TEST.md)
- [地图服务测试说明](地图服务_TEST.md)

## 状态与待办

README 不重复维护动态待办。当前完成情况、遗留问题和后续优先级统一以 [TODO 与差距总览](TODO与差距总览.md)为准；专项验收结果以对应 `*_TEST.md` 为准。
