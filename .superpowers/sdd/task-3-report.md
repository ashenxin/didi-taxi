# Task 3 实施报告：保护 passenger 内部认证入口

## 状态

已完成。`passenger` 的 `/api/v1/app/**` 与 `/api/v1/internal/**` 现在都要求请求头
`X-Internal-Service-Token`；新增权威认证状态读取与 epoch-CAS 登出接口，并保持其它路径不受该过滤器影响。

提交：`00b803b`（`功能：保护乘客内部认证状态接口`）

## RED 证据

### 安全过滤与启动校验

命令：

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalSecurityStartupValidatorTest test
```

结果：`BUILD FAILURE`。测试编译阶段按预期报告缺少
`PassengerInternalAuthFilter`、`PassengerInternalAuthProperties`，证明测试先于实现存在。

### 内部认证 Controller

命令：

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthControllerTest test
```

结果：`BUILD FAILURE`。测试编译阶段按预期报告缺少
`PassengerInternalAuthController`，证明 Controller 契约测试先于实现存在。

## GREEN 证据

### 安全层首次 GREEN

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalSecurityStartupValidatorTest test
```

结果：13 个测试通过，0 失败、0 错误、0 跳过。

实现过程中第一次运行曾得到 3 个行为失败：`MockHttpServletRequest` 的 `servletPath` 为空，导致受保护 URI 未命中。
保持测试不变，将路径判定改为 `requestURI` 并剥离 context path 后转为 GREEN。

### Controller GREEN

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthControllerTest test
```

结果：5 个测试通过，0 失败、0 错误、0 跳过。

### 任务聚焦验证

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalSecurityStartupValidatorTest,PassengerInternalAuthControllerTest,PassengerAuthEpochServiceIntegrationTest test
```

结果：29 个测试通过，0 失败、0 错误、0 跳过。其中既有 `PassengerAuthEpochServiceIntegrationTest`
的 11 个真实 Spring/MyBatis 集成测试通过。

### 完整模块验证

```bash
mvn -pl passenger test
```

最终新鲜运行结果：110 个测试通过，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。

## 变更文件

新增：

- `passenger/src/main/java/com/sx/passenger/internal/security/PassengerInternalAuthProperties.java`
- `passenger/src/main/java/com/sx/passenger/internal/security/PassengerInternalAuthFilter.java`
- `passenger/src/main/java/com/sx/passenger/internal/security/PassengerInternalSecurityStartupValidator.java`
- `passenger/src/main/java/com/sx/passenger/internal/auth/PassengerInternalAuthController.java`
- `passenger/src/main/java/com/sx/passenger/internal/auth/dto/InternalAuthStateResponse.java`
- `passenger/src/main/java/com/sx/passenger/internal/auth/dto/InternalLogoutRequest.java`
- `passenger/src/main/java/com/sx/passenger/internal/auth/dto/InternalLogoutResponse.java`
- `passenger/src/test/java/com/sx/passenger/internal/security/PassengerInternalAuthFilterTest.java`
- `passenger/src/test/java/com/sx/passenger/internal/security/PassengerInternalSecurityStartupValidatorTest.java`
- `passenger/src/test/java/com/sx/passenger/internal/auth/PassengerInternalAuthControllerTest.java`

修改：

- `passenger/src/main/java/com/sx/passenger/common/enums/ExceptionCode.java`
- `passenger/src/main/java/com/sx/passenger/common/exception/GlobalExceptionHandler.java`
- `passenger/src/main/resources/application.yml`
- `passenger/src/test/resources/application-test.yml`

## 自审

- Filter 使用 `MessageDigest.isEqual` 对 UTF-8 字节做常量时间比较；缺失 Header 返回 HTTP 401，错误值返回 HTTP 403。
- 路径边界为 `/api/v1/app`、`/api/v1/app/**`、`/api/v1/internal`、`/api/v1/internal/**`；其它 passenger 路径不受影响。
- Filter 与新增异常处理日志只记录 URI、requestId、状态码，不记录 Header、Token、OTP 或手机号。
- Controller 只调用 `PassengerAuthEpochService.loadState/logout` 并映射 DTO；未访问 mapper，未复制 lifecycle 判定规则。
- 不存在或不允许的权威状态保持 HTTP 200 与 `allowed=false`；`authEpoch=0` 正常序列化。
- stale logout 由统一异常处理映射 HTTP 409；Spring `DataAccessException` 映射 HTTP 503。
- 非 `local/dev/test` profile 对空值、UTF-8 少于 32 bytes、`dev-passenger-` 前缀及包含 `change-me` 的值启动失败；放宽 profile 有固定测试。
- 配置只从 `PASSENGER_INTERNAL_TOKEN` 绑定；仓库中没有真实内部令牌，也没有加入 mTLS 或公开 lifecycle Controller。
- `git diff --check` 通过；敏感日志/直接打印扫描无新增命中。

## 顾虑

- 完整测试仍输出仓库既存提示：两个 MyBatis 关联实体无主键、一个既存测试存在 unchecked 操作，以及 ByteBuddy 动态 agent 提示；均非本任务引入且不影响测试结果。
- 503 映射以 Spring `DataAccessException` 为基础设施故障边界，覆盖当前 MyBatis/Spring 数据访问异常；未来若该 service 引入非 Spring 数据访问客户端，需要为其异常类型补充同等映射。

## Critical 安全评审修复：矩阵参数鉴权绕过

修复提交：`3d7b062`（`修复：关闭内部接口矩阵参数鉴权绕过`）

### 评审验证与根因

评审指出原 Filter 使用原始 `requestURI`，而 Spring MVC 会把路径段中的分号内容作为矩阵参数处理。
真实 MockMvc 链路同时装配 `PassengerInternalAuthFilter`、`PassengerInternalAuthController` 和既有
`AppCustomerAuthController` 后确认：

- `/api/v1/internal;probe/auth-state/7` 无 Token 实际命中内部 Controller 并返回 200。
- `/api/v1/app;probe/auth/login-password` 无 Token 实际命中 app Controller 并返回 200。
- 带正确 Token 的 internal 矩阵路径能按正常 Controller 契约返回权威状态，排除了“不存在路由”的假象。

根因是安全层与 MVC 层使用了不同的路径规范化语义，而不是 Controller 或令牌比较逻辑错误。

### 修复 RED

在不修改 Filter 的前提下运行：

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalAuthControllerTest test
```

结果：15 个测试中 3 个按预期失败，均为预期 401、实际 200：internal 矩阵路径、app 矩阵路径、
带 contextPath 的 internal 矩阵路径。无关相似路径 `/api/v1/internally;probe/...` 仍通过放行测试。

### 修复与安全理由

`PassengerInternalAuthFilter.shouldNotFilter` 改用 Spring 6 的
`ServletRequestPathUtils.parseAndCache(request)` 与 `getCachedPathValue(request)`。该路径视图基于
`pathWithinApplication`，自动处理 contextPath，并按 Spring MVC 语义移除矩阵参数内容；Filter 不再依赖
`servletPath` 或自定义字符串剥离规则。规范化后仍沿用原有精确前缀边界判断，因此不会把
`/api/v1/internally...` 等无关路径扩大到保护范围。

### 修复 GREEN

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalAuthControllerTest test
```

结果：15 个测试通过，0 失败、0 错误、0 跳过。

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalSecurityStartupValidatorTest,PassengerInternalAuthControllerTest,PassengerAuthEpochServiceIntegrationTest test
```

结果：Task 3 聚焦测试 34 个通过，0 失败、0 错误、0 跳过。

```bash
mvn -pl passenger test
```

结果：完整 passenger 测试 115 个通过，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。

## Critical 安全复审修复：百分号编码路径鉴权绕过

修复提交：`57282f8`（`修复：关闭内部接口编码路径鉴权绕过`）

### 复审验证与根因

矩阵参数修复使用 `ServletRequestPathUtils.getCachedPathValue` 将结构化 `RequestPath` 降为字符串；该字符串仍保留
百分号编码，而 Spring `PathPattern` 路由按解码后的 segment 匹配。真实 MockMvc 链路确认：

- `/api/v1/%69nternal/auth-state/7` 无 Token 实际命中内部 Controller 并返回 200。
- `/api/v1/%61pp/auth/login-password` 无 Token 实际命中 app Controller 并返回 200。
- 带正确 Token 的 encoded internal 路径能按 Controller 契约返回权威状态，证明路由真实可达。

根因是 Filter 将 MVC 已解析的结构化路径降级为原始编码字符串后进行前缀比较，丢失了 PathPattern 的 segment
解码语义。

### 编码路径 RED

生产 Filter 保持不变，仅新增真实 MockMvc 回归测试后运行：

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthControllerTest test
```

结果：11 个测试中 2 个按预期失败，encoded internal/app 无令牌请求均为预期 401、实际 200；正确 Token
的 encoded internal 路径测试通过。

### 结构化 PathPattern 修复与安全理由

Filter 预编译四个 Spring `PathPattern`：`/api/v1/app`、`/api/v1/app/**`、
`/api/v1/internal`、`/api/v1/internal/**`。`shouldNotFilter` 直接使用
`ServletRequestPathUtils.parseAndCache(request).pathWithinApplication()` 返回的结构化 `PathContainer` 进行
`PathPattern.matches`，不再调用 `getCachedPathValue`，也不自行做 URL decode。

因此 Filter 与 Spring MVC 共享同一 PathPattern/PathContainer segment 语义，同时覆盖根路径、子路径、
矩阵参数、contextPath 和百分号编码。原有无关相似路径放行测试继续通过，保护范围未扩大到
`/api/v1/internally...`。

### 编码路径 GREEN

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalAuthControllerTest test
```

结果：18 个测试通过，0 失败、0 错误、0 跳过。

```bash
mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalSecurityStartupValidatorTest,PassengerInternalAuthControllerTest,PassengerAuthEpochServiceIntegrationTest test
```

结果：Task 3 聚焦测试 37 个通过，0 失败、0 错误、0 跳过。

```bash
mvn -pl passenger test
```

结果：完整 passenger 测试 118 个通过，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
