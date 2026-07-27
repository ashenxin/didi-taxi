# 业务服务 Nacos 本地配置运行手册（以 capacity-service 为例）

## 适用范围

除明确保留本地 `application.properties` 的 `xxl-job-admin` 外，项目中的所有业务
服务都采用与 `capacity-service` 相同的 Nacos 本地配置模式：使用 `local`
Namespace、`DIDI_TAXI` Group、单个必选 YAML Data ID，关闭热刷新，并在远程
配置缺失或加载标记不为 `true` 时终止启动。

已接入的配置如下：

| 模块 | Data ID |
| --- | --- |
| `capacity` | `capacity-service-local.yml` |
| `calculate` | `calculate-service-local.yml` |
| `passenger` | `passenger-service-local.yml` |
| `order` | `order-service-local.yml` |
| `wallet` | `wallet-service-local.yml` |
| `map` | `map-service-local.yml` |
| `admin-api` | `admin-api-local.yml` |
| `passenger-api` | `passenger-api-local.yml` |
| `driver-api` | `driver-api-local.yml` |
| `gateway` | `gateway-local.yml` |

下文使用 `capacity-service` 演示操作。其他业务服务只需替换 Data ID、加载标记、
Maven 模块名、启动类、端口和服务专属验证接口，其余流程相同。

## 服务注册与负载均衡

除 `xxl-job-admin` 外，全部业务服务都采用与 `capacity-service` 相同的 Nacos
服务注册方式，并统一注册到 `local` Namespace、`DIDI_TAXI` Group：

| 模块 | Nacos 服务名 |
| --- | --- |
| `capacity` | `capacity-service` |
| `calculate` | `calculate-service` |
| `passenger` | `passenger-service` |
| `order` | `order-service` |
| `wallet` | `wallet-service` |
| `map` | `map-service` |
| `admin-api` | `admin-api` |
| `passenger-api` | `passenger-api` |
| `driver-api` | `driver-api` |
| `gateway` | `gateway` |

服务间 OpenFeign 调用不再配置固定 IP 和端口，而是直接使用上述 Nacos 服务名，
由 Spring Cloud LoadBalancer 选择健康实例。同一个服务对应多个 Feign 接口时，
通过独立 `contextId` 隔离客户端上下文。

`wallet-service` 通知订单服务的 `RestClient` 同样使用负载均衡构建器，其
`services.order.base-url` 配置为 `http://order-service`。

Gateway 的 Nacos 配置 `gateway-local.yml` 使用以下负载均衡目标：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: admin-bff
          uri: lb://admin-api
        - id: passenger-bff
          uri: lb://passenger-api
        - id: driver-bff
          uri: lb://driver-api
```

路由原有的 `predicates`、CORS、JWT 和管理端点配置必须保留。`lb://` 仅替换
原来的固定 `http://127.0.0.1:<port>` URI。

## 配置身份

- 控制台：`http://127.0.0.1:8080`
- 客户端 API：`127.0.0.1:8848`
- Namespace：`local`（客户端使用控制台生成的 ID，不使用显示名称）
- Group：`DIDI_TAXI`
- Data ID：`capacity-service-local.yml`
- 格式：YAML
- 刷新：关闭；配置发布后重启服务生效
- 加载标记：`capacity.nacos.config-loaded: true`（不得删除或改为 false）

## 启动

在 zsh 中执行：

```bash
read -r "NACOS_NAMESPACE?Nacos namespace ID: "
export NACOS_NAMESPACE
export NACOS_USERNAME='nacos'
read -s "NACOS_PASSWORD?Nacos password "
export NACOS_PASSWORD
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

`NACOS_NAMESPACE` 必须填写控制台生成的 namespace ID。`NACOS_PASSWORD`
不应写入 shell history、Git、文档或普通聊天消息。

## 正常验证

在另一个终端中执行：

```bash
curl --fail --silent http://127.0.0.1:8090/actuator/health
lsof -nP -iTCP:8090 -sTCP:LISTEN
curl --fail --silent http://127.0.0.1:8090/test/sleuth
```

启动日志应显示成功加载 `DIDI_TAXI/capacity-service-local.yml`，且不应打印
Nacos 密码。健康检查应返回 HTTP 2xx，Java 进程应监听 8090；只读测试接口
响应中的 `code` 应为 200，`data` 应为 `Hello sleuth capacity`。

## 配置变更

1. 在 Nacos 的 `local` 命名空间中编辑并发布
   `DIDI_TAXI/capacity-service-local.yml`。
2. 重启 `capacity-service`。
3. 再次执行健康检查和相关只读接口验证。

第一阶段关闭热刷新。不要通过动态刷新判断配置是否生效。

## 秘密值

业务秘密值可以保存在 Nacos 配置中，例如地图服务密钥、数据库密码和业务访问
令牌。Nacos 自身的登录密码仍通过 `NACOS_PASSWORD` 注入，不能写入仓库中的
YAML 文件。

发布秘密值前应确认当前 Nacos 仅在受信任的本地环境中使用，并避免在截图、
终端日志、提交记录或排障消息中复制配置全文。

## 常见错误

- `NACOS_NAMESPACE` 缺失：设置命名空间 ID，不要填写显示名称 `local`。
- 认证失败：重新设置 `NACOS_USERNAME` 和 `NACOS_PASSWORD`。
- Data ID 不存在：核对 namespace ID、Group 和 Data ID 三元组。
- 日志提示必选 Nacos 配置未加载：确认远程文档包含加载标记，且配置不是空文档。
- 8848 不通：检查 Nacos API 进程和端口；8080 仅是 Nacos 3 控制台。
- 配置发布后没有变化：重启 `capacity-service`；当前未启用热刷新。
- 启动时缺少业务属性：确认 Nacos 文档是完整的本地有效配置，而不是局部覆盖。

## 回滚

恢复上一提交中的 `capacity/pom.xml` 和
`capacity/src/main/resources/application.yml`，删除
`capacity/src/main/resources/application-local.yml` 与边界测试，然后执行：

```bash
mvn -pl capacity test
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

Nacos 中的 Data ID 可以保留；客户端不再导入后不会影响应用。
