# 运力服务 Nacos 本地配置实施计划

> **供执行代理使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 子技能，逐任务执行本计划。各步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 在不启用服务发现的前提下，将 `capacity-service` 在本地环境实际生效的完整配置（包括业务秘密值）迁移到 Nacos 的 `didi-taxi-local` 命名空间。

**架构：** 应用身份和 Nacos Config Data 引导配置保留在本地 classpath 文件中，运行配置从 Nacos 的一个必选 YAML Data ID 加载。Nacos 导入声明只放在 `application-local.yml`，使测试环境保持隔离；发布到 Nacos 的是扁平化后的本地有效配置，配置变更后通过重启服务生效。

**技术栈：** Java 21、Spring Boot 3.3.5、Spring Cloud 2023.0.5、Spring Alibaba Nacos Config 2023.0.3.3、Nacos Server 3.2.3、Maven、JUnit 5、SnakeYAML。

## 全局约束

- 本次范围仅包括 `capacity-service`。
- 只创建 Nacos 命名空间 `didi-taxi-local`。
- 使用 Group `DIDI_TAXI` 和 Data ID `capacity-service-local.yml`。
- 客户端配置必须使用生成的 namespace ID，不能使用显示名称。
- 使用 `spring.config.import`，不创建 `bootstrap.yml`。
- 使用必选的 `nacos:` 导入并设置 `refreshEnabled=false`，不使用 `optional:nacos:`。
- 不添加 Nacos Discovery，不修改任何固定 HTTP 服务地址。
- 在本地 `application.yml` 中保留 `spring.application.name=capacity-service`。
- 业务秘密值存入 Nacos；Nacos 自身的用户名和密码仍通过 `NACOS_USERNAME`、`NACOS_PASSWORD` 传入。
- 不提交秘密值、生成的 namespace ID、访问令牌或临时 Nacos 配置载荷文件。
- 测试必须使用 `test` profile，并且不得连接 Nacos。
- 不修改 Spring Boot、Spring Cloud、数据库、Redis、Kafka 或 XXL-JOB 的版本。
- 保留无关的未跟踪文件 `passenger-api/src/main/java/com/sx/passengerapi/service/PassengerSettingsService 2.java`。

---

## 文件地图

- 修改 `capacity/pom.xml`：添加独立的 Nacos Config 依赖。
- 修改 `capacity/src/main/resources/application.yml`：精简为只包含应用身份。
- 创建 `capacity/src/main/resources/application-local.yml`：只包含 Nacos 引导和导入配置。
- 创建 `capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java`：在不连接 Nacos 的情况下约束本地与远程配置边界。
- 创建 `docs/runbooks/capacity-service-Nacos本地配置运行手册.md`：记录命名空间、配置发布、启动、验证、故障行为和回滚方式。
- Nacos 外部状态：创建命名空间 `didi-taxi-local` 并发布 `DIDI_TAXI/capacity-service-local.yml`；仓库中不创建包含秘密值的对应载荷文件。

## 接口约定

- `local` profile 使用：
  - `NACOS_SERVER_ADDR`，默认值为 `127.0.0.1:8848`
  - `NACOS_NAMESPACE`，必填的 namespace ID
  - `NACOS_USERNAME`，默认值为 `nacos`
  - `NACOS_PASSWORD`，必填密码
- Nacos Config Data 提供与当前 `capacity/src/main/resources/application.yml` 相同的 Spring 属性键。
- Nacos 资源标识：
  - Namespace 显示名称：`didi-taxi-local`
  - Group：`DIDI_TAXI`
  - Data ID：`capacity-service-local.yml`
  - 类型：YAML
  - 刷新：关闭

---

### 任务 1：用失败测试锁定 classpath 配置边界

**文件：**
- 创建：`capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java`
- 读取：`capacity/src/main/resources/application.yml`
- 实施前预期不存在：`capacity/src/main/resources/application-local.yml`

**接口约定：**
- 输入：classpath 资源 `application.yml` 和 `application-local.yml`。
- 输出：一个可执行契约，保证基础 profile 不依赖 Nacos，且 local profile 只导入一个必选 Data ID。

- [ ] **步骤 1：创建边界测试**

```java
package com.sx.capacity.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NacosLocalConfigBoundaryTest {

    @Test
    void baseConfigContainsOnlyApplicationIdentity() {
        Map<String, Object> root = loadYaml("application.yml");
        assertEquals(Set.of("spring"), root.keySet());

        Map<String, Object> spring = map(root.get("spring"));
        assertEquals(Set.of("application"), spring.keySet());
        assertEquals(Map.of("name", "capacity-service"), map(spring.get("application")));
    }

    @Test
    void localConfigImportsOneMandatoryNacosDocument() {
        Map<String, Object> root = loadYaml("application-local.yml");
        assertEquals(Set.of("spring"), root.keySet());

        Map<String, Object> spring = map(root.get("spring"));
        Map<String, Object> config = map(spring.get("config"));
        assertEquals(
                List.of("nacos:capacity-service-local.yml?group=DIDI_TAXI&refreshEnabled=false"),
                config.get("import")
        );

        Map<String, Object> nacos = map(spring.get("nacos"));
        Map<String, Object> nacosConfig = map(nacos.get("config"));
        assertEquals("${NACOS_SERVER_ADDR:127.0.0.1:8848}", nacosConfig.get("server-addr"));
        assertEquals("${NACOS_NAMESPACE}", nacosConfig.get("namespace"));
        assertEquals("${NACOS_USERNAME:nacos}", nacosConfig.get("username"));
        assertEquals("${NACOS_PASSWORD}", nacosConfig.get("password"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String resourceName) {
        try (InputStream input = NacosLocalConfigBoundaryTest.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, () -> resourceName + " must exist");
            Object value = new Yaml().load(input);
            return (Map<String, Object>) value;
        } catch (Exception exception) {
            throw new AssertionError("Unable to read " + resourceName, exception);
        }
    }
}
```

- [ ] **步骤 2：运行新测试并确认按预期失败**

执行：

```bash
mvn -pl capacity -Dtest=NacosLocalConfigBoundaryTest test
```

预期：FAIL，因为当前 `application.yml` 仍包含运行配置，并且 `application-local.yml` 尚不存在。

- [ ] **步骤 3：确认失败源于文件结构，而不是尝试连接 Nacos**

检查测试输出。测试必须因断言或 `application-local.yml must exist` 失败，输出中不得出现连接 `127.0.0.1:8848` 的尝试。

---

### 任务 2：添加 Nacos 客户端和最小本地引导配置

**文件：**
- 修改：`capacity/pom.xml`
- 修改：`capacity/src/main/resources/application.yml`
- 创建：`capacity/src/main/resources/application-local.yml`
- 测试：`capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java`

**接口约定：**
- 输入：全局接口约定中定义的环境变量。
- 输出：从选定命名空间请求 `DIDI_TAXI/capacity-service-local.yml` 的 `local` profile。

- [ ] **步骤 1：添加独立的 Nacos Config 依赖**

在 `capacity/pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-alibaba-nacos-config</artifactId>
    <version>2023.0.3.3</version>
</dependency>
```

不要添加 `spring-cloud-starter-alibaba-nacos-discovery`、`spring-cloud-starter-bootstrap` 或 Spring Cloud Alibaba BOM。

- [ ] **步骤 2：将主应用配置精简为应用身份**

将 `capacity/src/main/resources/application.yml` 精确改为：

```yaml
spring:
  application:
    name: capacity-service
```

在 Nacos 配置发布完成前，通过 Git 历史和当前差异保留修改前内容；不要创建包含秘密值的备份文件。

- [ ] **步骤 3：创建本地 Nacos 引导配置**

创建 `capacity/src/main/resources/application-local.yml`：

```yaml
spring:
  config:
    import:
      - nacos:capacity-service-local.yml?group=DIDI_TAXI&refreshEnabled=false
  nacos:
    config:
      server-addr: "${NACOS_SERVER_ADDR:127.0.0.1:8848}"
      namespace: "${NACOS_NAMESPACE}"
      username: "${NACOS_USERNAME:nacos}"
      password: "${NACOS_PASSWORD}"
```

- [ ] **步骤 4：运行边界测试**

执行：

```bash
mvn -pl capacity -Dtest=NacosLocalConfigBoundaryTest test
```

预期：PASS，且没有连接 Nacos 的尝试。

- [ ] **步骤 5：检查依赖收敛情况**

执行：

```bash
mvn -pl capacity dependency:tree '-Dincludes=com.alibaba.cloud:*,com.alibaba.nacos:*'
```

预期：

- 存在 `com.alibaba.cloud:spring-alibaba-nacos-config:2023.0.3.3`。
- Maven 只选择了一个 Nacos Client 版本。
- 不存在 discovery starter。

然后执行：

```bash
mvn -pl capacity dependency:tree -Dverbose
```

检查 Spring Boot、Spring Framework 和 Spring Cloud 相关依赖。如果新依赖使 Maven 选择了比父 POM 管理版本更旧的替代版本，立即停止，不要通过局部版本覆盖规避。

- [ ] **步骤 6：运行 capacity 模块的全部测试**

执行：

```bash
mvn -pl capacity test
```

预期：BUILD SUCCESS；日志中不得出现连接 Nacos 的尝试。

- [ ] **步骤 7：提交客户端配置边界**

```bash
git add capacity/pom.xml \
  capacity/src/main/resources/application.yml \
  capacity/src/main/resources/application-local.yml \
  capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java
git commit -m "功能：接入运力服务 Nacos 本地配置入口"
```

---

### 任务 3：创建本地命名空间并发布完整运力配置

**文件：**
- 从 Git 历史版本读取：`capacity/src/main/resources/application.yml`
- 外部写入：Nacos 命名空间和配置
- 不创建仓库内的配置载荷文件。

**接口约定：**
- 输入：迁移前 `capacity/src/main/resources/application.yml` 中精确的基础文档和 local profile 文档。
- 输出：Nacos namespace ID，以及配置源 `didi-taxi-local / DIDI_TAXI / capacity-service-local.yml`。

- [ ] **步骤 1：打开 Nacos 3 控制台**

打开：

```text
http://127.0.0.1:8080
```

使用用户名 `nacos` 和安装时初始化的管理员密码登录。

- [ ] **步骤 2：创建命名空间**

在命名空间管理中创建：

```text
Namespace name: didi-taxi-local
Description: didi-taxi 本地开发配置
```

从控制台读取生成的 namespace ID，只输入当前 zsh 会话：

```bash
read -r "NACOS_NAMESPACE?Nacos namespace ID: "
export NACOS_NAMESPACE
```

输入 Nacos 返回的实际 ID，不要把它写入 Git 跟踪文件。

- [ ] **步骤 3：根据迁移前配置构建远程 YAML**

在不恢复文件的情况下，从已确认设计的提交中读取原配置：

```bash
git show ad59237:capacity/src/main/resources/application.yml
```

严格按照以下规则构建 Nacos 文档：

1. 以旧文件的第一个 YAML 文档为基础。
2. 删除 `spring.application.name`。
3. 删除已注释的 Nacos discovery 配置块。
4. 保留 `server`、数据源、Redis、Kafka、日志、MyBatis、服务地址、capacity 和 XXL-JOB 的键及其当前标量值。
5. 在 Nacos 内容中保留当前数据库密码和其他业务秘密值；不得将其复制到仓库文件、终端记录、聊天消息或提交中。
6. 将以下实际生效的 local 覆盖项合并到现有 `capacity` 树：

```yaml
capacity:
  dispatch:
    geo-pin:
      900006:
        lat: 30.2525
        lng: 120.2156
  app:
    driver-auth:
      min-interval-seconds: 2
      daily-sms-limit-per-phone: 100
      daily-login-fail-limit-per-phone: 100
      mock-send-enabled: true
```

7. 不包含 `---`、`spring.config.activate.on-profile` 或仅供 dev 使用的 SQL 日志文档。
8. 确保最终 YAML 只有一个 `capacity` 映射：合并嵌套键，不创建重复的顶层键。

- [ ] **步骤 4：发布配置**

在配置管理中选择命名空间 `didi-taxi-local`，然后创建：

```text
Data ID: capacity-service-local.yml
Group: DIDI_TAXI
Configuration format: YAML
Description: capacity-service 本地完整配置，修改后重启生效
```

粘贴扁平化后的 YAML，预览差异后发布。

- [ ] **步骤 5：验证已发布的配置源**

重新打开配置详情并确认：

- Namespace 为 `didi-taxi-local`。
- Group 为 `DIDI_TAXI`。
- Data ID 为 `capacity-service-local.yml`。
- 文档包含 `server.port: 8090`。
- 文档包含数据源、Redis、Kafka、MyBatis、`services.order`、`capacity` 和 `xxl.job`。
- 文档包含本地 GEO 固定坐标和 `mock-send-enabled: true`。
- 文档不包含 `spring.application.name`、discovery 配置、profile 激活配置或多个 YAML 文档。

---

### 任务 4：添加运维运行手册

**文件：**
- 创建：`docs/runbooks/capacity-service-Nacos本地配置运行手册.md`
- 参考：`docs/superpowers/specs/2026-07-27-capacity-nacos-local-config-design.md`

**接口约定：**
- 输入：前述任务建立的 Nacos 资源标识和环境变量。
- 输出：不泄露秘密值、可重复执行的运维说明。

- [ ] **步骤 1：编写运行手册**

创建包含以下章节和命令的文件：

````markdown
# capacity-service Nacos 本地配置运行手册

## 配置身份

- 控制台：`http://127.0.0.1:8080`
- 客户端 API：`127.0.0.1:8848`
- Namespace：`didi-taxi-local`（客户端使用控制台生成的 ID）
- Group：`DIDI_TAXI`
- Data ID：`capacity-service-local.yml`
- 刷新：关闭；发布后重启服务

## 启动

```bash
read -r "NACOS_NAMESPACE?Nacos namespace ID: "
export NACOS_NAMESPACE
export NACOS_USERNAME='nacos'
read -s NACOS_PASSWORD
export NACOS_PASSWORD
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

`NACOS_PASSWORD` 不应写入 shell history、Git 或文档。

## 正常验证

```bash
curl --fail --silent http://127.0.0.1:8090/actuator/health
lsof -nP -iTCP:8090 -sTCP:LISTEN
```

启动日志应包含成功加载 `DIDI_TAXI/capacity-service-local.yml` 的信息，并且不应打印 Nacos 密码。

## 配置变更

在 Nacos 发布配置，重启 `capacity-service`，再执行健康检查。第一阶段不支持热刷新。

## 常见错误

- `NACOS_NAMESPACE` 缺失：设置命名空间 ID，不要填写显示名称。
- 认证失败：重新设置 `NACOS_USERNAME` 和 `NACOS_PASSWORD`。
- Data ID 不存在：检查 namespace、Group 和 Data ID 三元组。
- 8848 不通：检查 Nacos API 进程和端口；8080 仅是 Nacos 3 控制台。

## 回滚

恢复上一提交中的 `capacity/pom.xml` 和 `capacity/src/main/resources/application.yml`，删除 `application-local.yml` 与边界测试，执行：

```bash
mvn -pl capacity test
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

Nacos 中的 Data ID 可以保留；客户端不再导入后不会影响应用。
````

- [ ] **步骤 2：检查运行手册是否泄露秘密值**

执行：

```bash
rg -n 'password:|secret:|access-token:' docs/runbooks/capacity-service-Nacos本地配置运行手册.md
```

预期：不存在秘密值赋值；说明文字中允许出现 `NACOS_PASSWORD`。

- [ ] **步骤 3：提交运行手册**

```bash
git add docs/runbooks/capacity-service-Nacos本地配置运行手册.md
git commit -m "文档：补充运力服务 Nacos 本地运行手册"
```

---

### 任务 5：验证成功路径和快速失败行为

**文件：**
- 仅执行验证；不计划修改仓库文件。

**接口约定：**
- 输入：运行中的 Nacos 3.2.3 服务和已发布的 capacity Data ID。
- 输出：能够证明正常启动、离线测试和必选导入失败行为的命令结果。

- [ ] **步骤 1：运行干净的离线测试**

执行：

```bash
env -u NACOS_NAMESPACE -u NACOS_USERNAME -u NACOS_PASSWORD \
  mvn -pl capacity clean test
```

预期：BUILD SUCCESS，且没有访问 8848 端口。

- [ ] **步骤 2：使用 local profile 启动 capacity**

设置凭据，且不把密码写入 shell history：

```bash
read -r "NACOS_NAMESPACE?Nacos namespace ID: "
export NACOS_NAMESPACE
export NACOS_USERNAME='nacos'
read -s NACOS_PASSWORD
export NACOS_PASSWORD
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

预期：

- Nacos Config 报告成功从 `DIDI_TAXI` 加载 `capacity-service-local.yml`。
- Spring Boot 在 `8090` 端口启动。
- 日志不打印密码。

- [ ] **步骤 3：验证健康状态和端口**

在另一个终端中执行：

```bash
curl --fail --silent http://127.0.0.1:8090/actuator/health
lsof -nP -iTCP:8090 -sTCP:LISTEN
```

预期：健康检查返回 HTTP 2xx，并且有 Java 进程监听 8090。

- [ ] **步骤 4：验证一个现有只读接口**

```bash
curl --fail --silent http://127.0.0.1:8090/test/sleuth
```

预期：JSON 响应中的 `code` 为 200，`data` 等于 `Hello sleuth capacity`。

- [ ] **步骤 5：验证基于重启的配置变更**

在 Nacos 中记录以下配置的当前值：

```text
capacity.dispatch.match-radius-meters
```

发布一个无害的临时本地值，重启 `capacity-service`，确认启动环境或相关只读诊断接口读取到新值。随后恢复原值、重新发布并再次重启。

本测试不得修改秘密值、端口、数据源、Redis、Kafka 或 XXL-JOB 配置。

- [ ] **步骤 6：验证必选导入失败**

停止 Nacos 但不删除其数据，然后使用相同的本地环境变量再次启动 `capacity-service`：

```bash
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

预期：应用在 Config Data 加载阶段启动失败，服务不得监听 8090。

记录失败证据后重启 Nacos，并确认服务能够再次正常启动。

- [ ] **步骤 7：执行最终仓库检查**

执行：

```bash
mvn -pl capacity verify
git diff --check
git status --short
```

预期：

- Maven 报告 BUILD SUCCESS。
- `git diff --check` 没有输出。
- 只剩下原本就存在、与本任务无关的未跟踪文件 `PassengerSettingsService 2.java`；计划内文件均已提交。

---

## 完成证据

宣布完成前，在任务总结中保留以下结果：

- `spring-alibaba-nacos-config` 和 Maven 选定 Nacos Client 的依赖树记录。
- 清除 Nacos 环境变量后执行 `mvn -pl capacity clean test` 的结果。
- local profile 成功启动时，包含正确 Data ID 和 Group 的日志。
- `/actuator/health` 响应和 8090 监听证据。
- Nacos 停止时必选导入失败的证据。
- 最终 `mvn -pl capacity verify`、`git diff --check` 和 `git status --short` 的结果。
