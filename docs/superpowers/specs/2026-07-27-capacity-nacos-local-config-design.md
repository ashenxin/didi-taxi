# capacity-service 接入 Nacos 本地配置中心设计

## 背景

`didi-taxi` 当前使用 Spring Boot 3.3.5、Spring Cloud 2023.0.5 和 Java 21。各服务的运行配置保存在各模块的 `application*.yml` 中，尚未正式引入 Spring Cloud Alibaba。`capacity-service` 的配置覆盖 MySQL、Redis、Kafka、XXL-JOB、MyBatis、日志和派单业务参数，适合作为第一个 Nacos 配置中心试点。

本次只接入配置中心，不启用 Nacos 服务注册发现，也不改变现有服务间的 HTTP 地址。后续服务按同一模式逐个迁移；注册发现作为独立阶段设计和实施。

## 目标

- 为本地环境建立独立的 Nacos 命名空间 `didi-taxi-local`。
- 将 `capacity-service` 当前 local 环境的完整有效运行配置迁移到 Nacos。
- 保证应用在配置缺失、认证失败或 Nacos 不可用时快速失败。
- 保证单元测试不依赖正在运行的 Nacos。
- 保留清晰、低风险的回滚路径。

## 非目标

- 不创建 dev、test 或 prod 命名空间。
- 不接入 `spring-cloud-starter-alibaba-nacos-discovery`。
- 不把固定 HTTP 地址改为服务名。
- 不在第一阶段支持配置热刷新。
- 不迁移其他服务的配置。
- 不调整 Spring Boot、Spring Cloud、数据库、Redis、Kafka 或 XXL-JOB 的版本。

## 方案选择

采用“单服务单配置文件 + Spring Config Data 导入”：

- Namespace：`didi-taxi-local`
- Group：`DIDI_TAXI`
- Data ID：`capacity-service-local.yml`
- 配置格式：YAML
- 导入方式：`spring.config.import`
- 刷新策略：`refreshEnabled=false`

不采用公共配置与服务配置拆分。当前只有一个试点服务，提前引入公共 Data ID 会增加加载顺序和跨服务耦合。待至少两个服务完成迁移并确认存在稳定、同义的共享配置后，再单独设计公共配置。

不采用 `bootstrap.yml`。Spring Cloud Alibaba 2023.x 已推荐使用 Spring Config Data，旧式 bootstrap 加载会增加后续升级成本。

## 依赖设计

`capacity` 模块引入：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-alibaba-nacos-config</artifactId>
    <version>2023.0.3.3</version>
</dependency>
```

选择独立的 `spring-alibaba-nacos-config`，而不是完整的 `spring-cloud-starter-alibaba-nacos-config`。该模块不依赖 Spring Cloud，能减少项目现有 Spring Boot 3.3.5、Spring Cloud 2023.0.5 与 Spring Cloud Alibaba 2023.x 版本线之间的耦合。

实施时必须通过 Maven 依赖树、编译和测试确认它与当前项目版本组合兼容。若出现依赖冲突，不通过覆盖 Spring Framework 或 Spring Boot 单个组件版本规避；应暂停接入并重新评估整体版本矩阵。

## 本地文件边界

### `application.yml`

只保留应用身份：

```yaml
spring:
  application:
    name: capacity-service
```

这使默认和 test profile 不会自动连接 Nacos。

### `application-local.yml`

只保留定位和加载远程配置所必需的信息：

```yaml
spring:
  config:
    import:
      - nacos:capacity-service-local.yml?group=DIDI_TAXI&refreshEnabled=false
  nacos:
    config:
      server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
      namespace: ${NACOS_NAMESPACE}
      username: ${NACOS_USERNAME:nacos}
      password: ${NACOS_PASSWORD}
```

约束：

- `NACOS_NAMESPACE` 必须填写 Nacos 创建命名空间后生成的 namespace ID，不能填写显示名称 `didi-taxi-local`。
- `NACOS_PASSWORD` 必须由运行环境传入，不提交到 Git。
- Data ID 和 Group 固定在导入声明中，避免服务启动时意外读取其他配置。
- 使用必选的 `nacos:`，不使用 `optional:nacos:`。

### 测试配置

`src/test/resources/application-test.yml` 保持测试专用配置。测试不激活 `local` profile，因此不会导入 Nacos，也不会发起远程连接。

## Nacos 配置内容

`capacity-service-local.yml` 保存当前 local 环境最终生效的完整配置：

- `server.port`
- `spring.datasource`
- `spring.data.redis`
- `spring.kafka`
- `logging`
- `mybatis-plus`
- `services`
- `capacity`
- `xxl.job`

原 `application.yml` 中的基础配置和 `local` profile 覆盖项需要合并为一份扁平的 local 配置。具体包括：

- 保留当前服务端口 `8090`。
- 保留当前 MySQL、Redis、Kafka 和 XXL-JOB 本地连接方式。
- 合并 local profile 的固定 GEO 测试坐标。
- 合并 local profile 中司机短信 mock 开关和限制参数。
- 不复制只属于 dev profile 的日志覆盖段。
- 不把 `spring.application.name` 放入远程配置，避免应用身份依赖远程加载结果。
- 不加入 discovery 配置。

配置中的秘密值可以继续使用环境变量占位符。Nacos 只负责集中存储配置，不替代专门的密钥管理系统；生产环境设计时必须重新评估敏感配置的存储和访问控制。

## 启动与数据流

1. 使用 `local` profile 启动 `capacity-service`。
2. Spring Boot 读取本地 `application.yml`，确定应用名。
3. Spring Boot 读取 `application-local.yml`，取得 Nacos API 地址、namespace ID 和凭据。
4. Config Data Loader 从 `DIDI_TAXI` Group 加载 `capacity-service-local.yml`。
5. 远程配置加入 Spring Environment。
6. Spring Boot 根据远程配置创建 Web Server、数据源、Redis、Kafka、MyBatis 和 XXL-JOB 相关 Bean。
7. 服务在 `8090` 端口启动。

## 刷新策略

第一阶段关闭动态刷新。发布 Nacos 配置后，需要重启 `capacity-service` 才生效。

原因：

- 端口、数据源、Redis、Kafka、日志和 XXL-JOB 等配置不适合无边界热更新。
- 当前业务参数没有统一的动态刷新契约和验证机制。
- 全量配置单 Data ID 开启刷新会让可刷新参数与必须重启的参数混在一起，产生错误预期。

后续如需动态调节派单半径、确认窗口或扫描频率，应把明确允许热更新的业务参数拆入独立 Data ID，并为对应配置 Bean、线程安全性和运行时行为单独设计和测试。

## 故障处理

- Nacos API 端口不可达：应用启动失败。
- 用户名或密码错误：应用启动失败并输出认证错误，但日志不得打印密码。
- namespace ID、Group 或 Data ID 错误：应用启动失败。
- Nacos 配置 YAML 无法解析：应用启动失败并指出配置来源。
- MySQL、Redis、Kafka 或 XXL-JOB 不可用：维持各组件当前的启动和重试语义，不由 Nacos 接入额外吞掉错误。
- Nacos 控制台运行在 8080 端口；客户端读取配置仍使用 Nacos API 端口 8848。

## 验证设计

### 静态和构建验证

1. 检查 Maven 依赖树，确认没有 Spring Boot、Spring Framework、Spring Cloud 的意外降级或多版本冲突。
2. 执行 `capacity` 模块测试，确认测试过程没有访问 Nacos。
3. 执行 `capacity` 模块打包或 verify。

### 集成验证

1. 在 Nacos 中创建 `didi-taxi-local` 命名空间并记录 namespace ID。
2. 在该命名空间、`DIDI_TAXI` Group 中发布 `capacity-service-local.yml`。
3. 设置 `NACOS_NAMESPACE`、`NACOS_USERNAME`、`NACOS_PASSWORD`。
4. 使用 `local` profile 启动 `capacity-service`。
5. 从启动日志确认目标 namespace、Group 和 Data ID 加载成功。
6. 确认服务监听 `8090`。
7. 调用 `/actuator/health` 和一个现有业务接口。
8. 验证 MySQL、Redis、Kafka 和 XXL-JOB 的连接行为与迁移前一致。
9. 修改一个非敏感派单参数，发布后重启服务，确认新值生效。
10. 暂停 Nacos 后再次启动服务，确认服务快速失败。

验收要求：

- 本地配置文件不再含已迁移的运行配置。
- 服务只从指定 namespace、Group 和 Data ID 读取配置。
- 测试不依赖 Nacos。
- 正常启动与故障启动行为都有可复现证据。

## 回滚设计

若接入失败：

1. 恢复迁移前的 `capacity/src/main/resources/application.yml`。
2. 删除 `application-local.yml` 中的 Nacos 导入配置，或恢复其迁移前内容。
3. 删除 `capacity` 模块新增的 Nacos Config 依赖。
4. 重新执行模块测试并使用 local profile 启动。

Nacos 中已发布的 Data ID 可以保留，它在客户端不再导入后不会影响应用。若需要清理，应通过 Nacos 控制台删除对应配置，避免删除整个命名空间或其他服务配置。

## 后续阶段

`capacity-service` 验收通过后，按相同模式逐个迁移其他服务。所有目标服务完成配置中心迁移并稳定运行后，再单独设计：

- Nacos Discovery 依赖和版本矩阵。
- 服务注册命名、Group、namespace 和健康检查。
- Gateway 路由从固定 URI 迁移到负载均衡服务 URI。
- OpenFeign/HTTP 客户端从固定 base URL 迁移到服务名。
- 固定地址与服务发现之间的灰度和回滚策略。
