# Task 4 实施报告：以 ae/scope 强校验 passenger-api HTTP 与 WS 会话

## 状态

- Status: DONE
- Base: `57282f8`
- Commit: `a3a3bd5`
- 提交信息：`功能：以认证代次强校验乘客HTTP与WS令牌`

## 实施结果

1. JWT 只创建和解析 `aud=app-bff`、`ae`、`scope`、`audit`、`operationNo` 等严格会话材料。
   - `ae >= 1`。
   - `NORMAL` 只接受 `audit=1/2` 且 `operationNo=null`。
   - `LIFECYCLE_RESTRICTED` 只接受 `audit=1` 且非空 `operationNo`。
   - 删除 `tv` claim 解析和 audit 缺失默认值；数字字符串等宽松类型不再接受。
2. HTTP Filter 对每个可解析的受保护请求恰好调用一次 passenger 内部 auth-state 接口。
   - customerId、authEpoch、scope、operationNo、lifecycle、channel audit 或 `allowed` 任一不一致返回 401。
   - 当前受限生命周期路径集合为空；权威状态一致的受限 Token 访问普通业务返回 403。
   - passenger Feign 5xx、连接/重试异常及异常响应包装返回 503，未设置降级放行。
   - 公开短信/密码登录路径保持跳过。
   - 放行后覆盖注入 `X-User-Id`、`X-User-Phone`、`X-Auth-Epoch`、`X-Auth-Scope`、`X-Lifecycle-Operation-No`，不信任同名入站头。
3. 新增 passenger core auth-state/logout Feign 契约与 DTO。
4. `X-Internal-Service-Token` 拦截器只显式绑定 `PassengerCoreAuthClient`、`PassengerCoreSettingsClient`、`PassengerCoreAuthStateClient`；配置类未注册为全局 `@Configuration`，order/calculate/wallet/map 等客户端不受污染。
5. WS 握手移除 `PassengerTokenVersionStore` 依赖，改为每个可解析 Token 回查一次 DB 权威状态。
   - `NORMAL + audit=2 + ACTIVE + epoch/operationNo 一致` 才放行。
   - 权威一致的受限 Token 返回 403；签名、epoch、scope、operationNo、customer、audit 不一致返回 401；passenger 不可用返回 503。
   - 按 Task 4/Task 8 边界保留 `PassengerTokenVersionStore.java` 文件。

## RED / GREEN 记录

### Round 1：JWT 严格契约

- RED：`mvn -pl passenger-api -Dtest=AppJwtServiceTest test`
  - 结果：FAIL（testCompile），缺少 `PassengerSessionScope` 和新签发接口，4 个编译错误。
- GREEN：同命令。
  - 结果：PASS，5 tests，0 failures，0 errors。

### Round 2：HTTP 权威校验矩阵

- RED：`mvn -pl passenger-api -Dtest=PassengerJwtAuthFilterTest test`
  - 结果：FAIL（testCompile），缺少 auth-state client/DTO/decision service，8 个编译错误。
- 首次 GREEN 检查：6 tests 中 1 failure、1 error；定位为测试夹具覆盖了 wrong-audit/operationNo stub，以及 Mockito 二次异常重桩触发旧异常，未放宽生产校验。
- GREEN：修正夹具后同命令。
  - 结果：PASS，6 tests，0 failures，0 errors。

### Round 3：Passenger core Feign Header 隔离

- RED：`mvn -pl passenger-api -Dtest=PassengerCoreFeignConfigurationTest test`
  - 结果：FAIL（testCompile），缺少 properties/configuration，5 个编译错误。
- GREEN：同命令。
  - 结果：PASS，2 tests，0 failures，0 errors。

### Round 4：WS 强校验

- RED：`mvn -pl passenger-api -Dtest=PassengerWsHandshakeInterceptorTest test`
  - 结果：FAIL（testCompile），现有构造器仍依赖 `PassengerTokenVersionStore`，1 个编译错误。
- GREEN：同命令。
  - 结果：PASS，5 tests，0 failures，0 errors。

## 最终验证

- 计划聚焦：`mvn -pl passenger-api -Dtest=AppJwtServiceTest,PassengerJwtAuthFilterTest,PassengerWsHandshakeInterceptorTest test`
  - PASS，16 tests，0 failures，0 errors。
- 相关既有认证/设置/安全/WS：`mvn -pl passenger-api -Dtest=PassengerAuthServiceTest,PassengerSettingsServiceTest,PassengerSecurityStartupValidatorTest,PassengerWsHandshakeInterceptorTest test`
  - PASS，11 tests，0 failures，0 errors。
- 模块全量：`mvn -pl passenger-api test`
  - PASS，53 tests，0 failures，0 errors。
- `tv` 兼容扫描：Task 4 JWT/Filter/WS 文件中无 `"tv"`、`claim("tv")`、`tokenVersion()`、audit 默认分支。
- WS 依赖扫描：WS main/test 中无 `PassengerTokenVersionStore` / `tokenVersionStore`。
- Feign 范围扫描：`PassengerCoreFeignConfiguration` 仅出现在三个 passenger core client 及其配置类。
- 敏感日志扫描：新增日志只记录异常类型；未记录 JWT、内部 Token 或手机号；真实 `PASSENGER_INTERNAL_TOKEN` 未读取、打印或硬编码。
- `git diff --check`：PASS。

## 文件

### 新建

- `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerSessionScope.java`
- `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerAuthContext.java`
- `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerAuthDecisionService.java`
- `passenger-api/src/main/java/com/sx/passengerapi/auth/InvalidPassengerSessionException.java`
- `passenger-api/src/main/java/com/sx/passengerapi/client/PassengerCoreAuthStateClient.java`
- `passenger-api/src/main/java/com/sx/passengerapi/client/dto/InternalAuthStateResponse.java`
- `passenger-api/src/main/java/com/sx/passengerapi/client/dto/InternalLogoutRequest.java`
- `passenger-api/src/main/java/com/sx/passengerapi/client/dto/InternalLogoutResponse.java`
- `passenger-api/src/main/java/com/sx/passengerapi/config/PassengerInternalClientProperties.java`
- `passenger-api/src/main/java/com/sx/passengerapi/config/PassengerCoreFeignConfiguration.java`
- `passenger-api/src/test/java/com/sx/passengerapi/auth/AppJwtServiceTest.java`
- `passenger-api/src/test/java/com/sx/passengerapi/auth/PassengerJwtAuthFilterTest.java`
- `passenger-api/src/test/java/com/sx/passengerapi/config/PassengerCoreFeignConfigurationTest.java`
- `passenger-api/src/test/java/com/sx/passengerapi/ws/PassengerWsHandshakeInterceptorTest.java`

### 修改

- `passenger-api/src/main/java/com/sx/passengerapi/auth/ParsedPassengerJwt.java`
- `passenger-api/src/main/java/com/sx/passengerapi/auth/AppJwtService.java`
- `passenger-api/src/main/java/com/sx/passengerapi/config/AppJwtProperties.java`
- `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerAuthRequestWrapper.java`
- `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerJwtAuthFilter.java`
- `passenger-api/src/main/java/com/sx/passengerapi/ws/PassengerWsHandshakeInterceptor.java`
- `passenger-api/src/main/java/com/sx/passengerapi/client/PassengerCoreAuthClient.java`
- `passenger-api/src/main/java/com/sx/passengerapi/client/PassengerCoreSettingsClient.java`
- `passenger-api/src/main/java/com/sx/passengerapi/config/AppJwtConfiguration.java`
- `passenger-api/src/main/resources/application.yml`

## 自审与顾虑

- Task 5 尚未负责登录/登出编排。为保持 Task 4 编译和既有调用兼容，暂保留 4 参数签发重载，但该重载只委托新接口生成严格 `ae/scope/audit` claims，不创建或解析 `tv`；Task 5 应切换调用点到 core 返回的 `authEpoch/scope/operationNo` 并移除此过渡入口。
- 当前受限 HTTP 生命周期路径集合按 brief 固定为空，因此受限 Token 即使权威一致也只会得到 403；后续必须由明确任务开放具体路径，不能在本任务猜测。
- `PassengerTokenVersionStore` 仍被现有登录/设置服务使用；按 Task 8 边界未删除文件，也未提前迁移这些编排。
- `PASSENGER_INTERNAL_TOKEN` 只通过配置占位符和专属 Feign 拦截器使用；本报告和测试均未接触用户 IDEA 中的真实值。
