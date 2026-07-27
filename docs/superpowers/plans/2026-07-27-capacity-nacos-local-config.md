# Capacity Nacos Local Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the complete effective local configuration of `capacity-service`, including its business secrets, into the `didi-taxi-local` Nacos namespace without enabling service discovery.

**Architecture:** Keep application identity and the Nacos Config Data bootstrap in local classpath files, while loading one mandatory YAML Data ID from Nacos. Keep tests isolated by placing the Nacos import only in `application-local.yml`; publish a flattened local configuration to Nacos and require restart for changes.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Cloud 2023.0.5, Spring Alibaba Nacos Config 2023.0.3.3, Nacos Server 3.2.3, Maven, JUnit 5, SnakeYAML.

## Global Constraints

- Only `capacity-service` is in scope.
- Create only the `didi-taxi-local` Nacos namespace.
- Use Group `DIDI_TAXI` and Data ID `capacity-service-local.yml`.
- Use the generated namespace ID, not the display name, in client configuration.
- Use `spring.config.import`; do not create `bootstrap.yml`.
- Use mandatory `nacos:` import with `refreshEnabled=false`; do not use `optional:nacos:`.
- Do not add Nacos Discovery or change any fixed HTTP service URL.
- Keep `spring.application.name=capacity-service` in local `application.yml`.
- Put business secrets in Nacos, but pass Nacos's own username and password through `NACOS_USERNAME` and `NACOS_PASSWORD`.
- Do not commit secret values, generated namespace IDs, access tokens, or temporary Nacos payload files.
- Tests must use the `test` profile and must not contact Nacos.
- Do not change Spring Boot, Spring Cloud, database, Redis, Kafka, or XXL-JOB versions.
- Preserve the unrelated untracked file `passenger-api/src/main/java/com/sx/passengerapi/service/PassengerSettingsService 2.java`.

---

## File Map

- Modify `capacity/pom.xml`: add the standalone Nacos Config dependency.
- Modify `capacity/src/main/resources/application.yml`: reduce it to application identity.
- Create `capacity/src/main/resources/application-local.yml`: contain only Nacos bootstrap and import properties.
- Create `capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java`: enforce the local/remote configuration boundary without contacting Nacos.
- Create `docs/runbooks/capacity-service-Nacos本地配置运行手册.md`: document namespace, publication, startup, verification, failure behavior, and rollback.
- External Nacos state: create namespace `didi-taxi-local` and publish `DIDI_TAXI/capacity-service-local.yml`; this is deliberately not represented by a secret-bearing repository file.

## Interfaces

- Local profile consumes:
  - `NACOS_SERVER_ADDR`, default `127.0.0.1:8848`
  - `NACOS_NAMESPACE`, required namespace ID
  - `NACOS_USERNAME`, default `nacos`
  - `NACOS_PASSWORD`, required password
- Nacos Config Data produces the same Spring property keys currently supplied by `capacity/src/main/resources/application.yml`.
- Nacos resource identity:
  - Namespace display name: `didi-taxi-local`
  - Group: `DIDI_TAXI`
  - Data ID: `capacity-service-local.yml`
  - Type: YAML
  - Refresh: disabled

---

### Task 1: Lock the classpath configuration boundary with a failing test

**Files:**
- Create: `capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java`
- Read: `capacity/src/main/resources/application.yml`
- Expected missing file before implementation: `capacity/src/main/resources/application-local.yml`

**Interfaces:**
- Consumes: the classpath resource names `application.yml` and `application-local.yml`.
- Produces: an executable contract that the base profile is Nacos-free and the local profile imports exactly one mandatory Data ID.

- [ ] **Step 1: Create the boundary test**

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

- [ ] **Step 2: Run the new test and verify the expected failure**

Run:

```bash
mvn -pl capacity -Dtest=NacosLocalConfigBoundaryTest test
```

Expected: FAIL because the current `application.yml` contains runtime configuration and `application-local.yml` does not yet exist.

- [ ] **Step 3: Confirm the failure is structural rather than a Nacos network attempt**

Inspect the test output. It must fail on an assertion or `application-local.yml must exist`; it must not contain a connection attempt to `127.0.0.1:8848`.

---

### Task 2: Add the Nacos client and minimal local bootstrap

**Files:**
- Modify: `capacity/pom.xml`
- Modify: `capacity/src/main/resources/application.yml`
- Create: `capacity/src/main/resources/application-local.yml`
- Test: `capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java`

**Interfaces:**
- Consumes: environment variables defined in the global interfaces section.
- Produces: a `local` profile that requests `DIDI_TAXI/capacity-service-local.yml` from the selected namespace.

- [ ] **Step 1: Add the standalone Nacos Config dependency**

Add this dependency inside `capacity/pom.xml`'s `<dependencies>`:

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-alibaba-nacos-config</artifactId>
    <version>2023.0.3.3</version>
</dependency>
```

Do not add `spring-cloud-starter-alibaba-nacos-discovery`, `spring-cloud-starter-bootstrap`, or a Spring Cloud Alibaba BOM.

- [ ] **Step 2: Replace the main application configuration with application identity**

Make `capacity/src/main/resources/application.yml` exactly:

```yaml
spring:
  application:
    name: capacity-service
```

Keep the pre-change file visible in Git history and the current diff until the Nacos payload has been published; do not create a secret-bearing backup file.

- [ ] **Step 3: Create the local Nacos bootstrap**

Create `capacity/src/main/resources/application-local.yml`:

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

- [ ] **Step 4: Run the boundary test**

Run:

```bash
mvn -pl capacity -Dtest=NacosLocalConfigBoundaryTest test
```

Expected: PASS, with no Nacos connection attempt.

- [ ] **Step 5: Inspect dependency convergence**

Run:

```bash
mvn -pl capacity dependency:tree '-Dincludes=com.alibaba.cloud:*,com.alibaba.nacos:*'
```

Expected:

- `com.alibaba.cloud:spring-alibaba-nacos-config:2023.0.3.3` is present.
- A single Nacos client line is selected by Maven.
- No discovery starter is present.

Then run:

```bash
mvn -pl capacity dependency:tree -Dverbose
```

Inspect Spring Boot, Spring Framework, and Spring Cloud lines. If the new dependency selects older replacements for the parent-managed versions, stop and do not add selective overrides.

- [ ] **Step 6: Run all capacity tests**

Run:

```bash
mvn -pl capacity test
```

Expected: BUILD SUCCESS; logs must not show a Nacos connection attempt.

- [ ] **Step 7: Commit the client boundary**

```bash
git add capacity/pom.xml \
  capacity/src/main/resources/application.yml \
  capacity/src/main/resources/application-local.yml \
  capacity/src/test/java/com/sx/capacity/config/NacosLocalConfigBoundaryTest.java
git commit -m "功能：接入运力服务 Nacos 本地配置入口"
```

---

### Task 3: Create the local namespace and publish the complete capacity configuration

**Files:**
- Read from Git parent revision: `capacity/src/main/resources/application.yml`
- External write: Nacos namespace and configuration
- Do not create a repository payload file.

**Interfaces:**
- Consumes: the exact pre-migration base and local profile documents from `capacity/src/main/resources/application.yml`.
- Produces: Nacos namespace ID and the configuration source `didi-taxi-local / DIDI_TAXI / capacity-service-local.yml`.

- [ ] **Step 1: Open the Nacos 3 console**

Open:

```text
http://127.0.0.1:8080
```

Log in as `nacos` with the administrator password initialized during installation.

- [ ] **Step 2: Create the namespace**

In Namespace Management, create:

```text
Namespace name: didi-taxi-local
Description: didi-taxi 本地开发配置
```

Read the generated namespace ID from the console into the current zsh session only:

```bash
read -r "NACOS_NAMESPACE?Nacos namespace ID: "
export NACOS_NAMESPACE
```

Enter the runtime ID returned by Nacos. Do not write it into Git-tracked files.

- [ ] **Step 3: Build the remote YAML from the pre-migration source**

Read the source from the parent revision without restoring it:

```bash
git show ad59237:capacity/src/main/resources/application.yml
```

Construct the Nacos document with these exact transformation rules:

1. Start with the first YAML document from the old file.
2. Remove `spring.application.name`.
3. Remove the commented Nacos discovery block.
4. Preserve `server`, datasource, Redis, Kafka, logging, MyBatis, service URL, capacity, and XXL-JOB keys and their current scalar values.
5. Preserve the current database password and other business secret values in the Nacos content; do not copy them into a repository file, terminal transcript, chat message, or commit.
6. Merge these effective local overrides into the existing `capacity` tree:

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

7. Do not include `---`, `spring.config.activate.on-profile`, or the dev-only SQL logging document.
8. Ensure the final YAML has one `capacity` mapping: merge nested keys instead of creating duplicate top-level keys.

- [ ] **Step 4: Publish the configuration**

In Configuration Management, select namespace `didi-taxi-local` and create:

```text
Data ID: capacity-service-local.yml
Group: DIDI_TAXI
Configuration format: YAML
Description: capacity-service 本地完整配置，修改后重启生效
```

Paste the flattened YAML, preview its diff, and publish it.

- [ ] **Step 5: Verify the published source**

Reopen the configuration detail and confirm:

- Namespace is `didi-taxi-local`.
- Group is `DIDI_TAXI`.
- Data ID is `capacity-service-local.yml`.
- The document contains `server.port: 8090`.
- It contains datasource, Redis, Kafka, MyBatis, `services.order`, `capacity`, and `xxl.job`.
- It contains the local GEO pin and `mock-send-enabled: true`.
- It does not contain `spring.application.name`, discovery configuration, profile activation, or multiple YAML documents.

---

### Task 4: Add the operational runbook

**Files:**
- Create: `docs/runbooks/capacity-service-Nacos本地配置运行手册.md`
- Reference: `docs/superpowers/specs/2026-07-27-capacity-nacos-local-config-design.md`

**Interfaces:**
- Consumes: the Nacos resource identity and environment variables established above.
- Produces: reproducible operator instructions that do not disclose secrets.

- [ ] **Step 1: Write the runbook**

Create the file with these sections and commands:

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

- [ ] **Step 2: Check the runbook for leaked values**

Run:

```bash
rg -n 'password:|secret:|access-token:' docs/runbooks/capacity-service-Nacos本地配置运行手册.md
```

Expected: no secret value assignments. The word `NACOS_PASSWORD` in explanatory text is allowed.

- [ ] **Step 3: Commit the runbook**

```bash
git add docs/runbooks/capacity-service-Nacos本地配置运行手册.md
git commit -m "文档：补充运力服务 Nacos 本地运行手册"
```

---

### Task 5: Verify success and fail-fast behavior

**Files:**
- Verify only; no planned repository modification.

**Interfaces:**
- Consumes: a running Nacos 3.2.3 server and the published capacity Data ID.
- Produces: captured command output proving normal startup, offline tests, and mandatory-import failure.

- [ ] **Step 1: Run clean offline tests**

Run:

```bash
env -u NACOS_NAMESPACE -u NACOS_USERNAME -u NACOS_PASSWORD \
  mvn -pl capacity clean test
```

Expected: BUILD SUCCESS, no request to port 8848.

- [ ] **Step 2: Start capacity with the local profile**

Set credentials without putting the password in shell history:

```bash
read -r "NACOS_NAMESPACE?Nacos namespace ID: "
export NACOS_NAMESPACE
export NACOS_USERNAME='nacos'
read -s NACOS_PASSWORD
export NACOS_PASSWORD
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

Expected:

- Nacos Config reports successful loading of `capacity-service-local.yml` from `DIDI_TAXI`.
- Spring Boot starts on port `8090`.
- The password is not printed.

- [ ] **Step 3: Verify health and port**

In another terminal:

```bash
curl --fail --silent http://127.0.0.1:8090/actuator/health
lsof -nP -iTCP:8090 -sTCP:LISTEN
```

Expected: health returns an HTTP 2xx response and a Java process listens on 8090.

- [ ] **Step 4: Verify one existing read-only endpoint**

```bash
curl --fail --silent http://127.0.0.1:8090/test/sleuth
```

Expected: the JSON response has `code` 200 and `data` equal to `Hello sleuth capacity`.

- [ ] **Step 5: Verify restart-based configuration changes**

In Nacos, record the current value of:

```text
capacity.dispatch.match-radius-meters
```

Publish a harmless temporary local value, restart `capacity-service`, and confirm the startup environment or a relevant read-only diagnostic endpoint observes it. Restore the original value, publish again, and restart once more.

Do not use a secret, port, datasource, Redis, Kafka, or XXL-JOB value for this test.

- [ ] **Step 6: Verify mandatory-import failure**

Stop Nacos without deleting its data, then start `capacity-service` again with the same local environment variables:

```bash
mvn -pl capacity spring-boot:run -Dspring-boot.run.profiles=local
```

Expected: startup fails during Config Data loading; the service must not listen on 8090.

Restart Nacos after capturing the failure and confirm the service starts normally again.

- [ ] **Step 7: Run final repository checks**

Run:

```bash
mvn -pl capacity verify
git diff --check
git status --short
```

Expected:

- Maven reports BUILD SUCCESS.
- `git diff --check` prints nothing.
- Only the pre-existing unrelated untracked `PassengerSettingsService 2.java` remains; all planned files are committed.

---

## Completion Evidence

Before declaring completion, retain these results in the task summary:

- Dependency tree lines for `spring-alibaba-nacos-config` and the selected Nacos client.
- `mvn -pl capacity clean test` result with Nacos environment variables removed.
- Successful local-profile startup log line naming the correct Data ID and Group.
- `/actuator/health` response and 8090 listener evidence.
- Mandatory-import failure evidence while Nacos is stopped.
- Final `mvn -pl capacity verify`, `git diff --check`, and `git status --short` results.
