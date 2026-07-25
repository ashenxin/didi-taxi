# Task 5 报告：重接 BFF 登录、WS Token 与先失效后清单的登出流程

## Status

PASS

## RED

- 首次聚焦命令：`mvn -pl passenger-api -Dtest=PassengerAuthServiceTest,PassengerWsSessionRegistryTest,PassengerSettingsServiceTest,PassengerAuthControllerTest test`
- 结果：FAIL（testCompile），36 个预期编译失败；缺少 `logout(customerId, authEpoch)`、`issueWsToken(PassengerAuthContext)`、`closeCustomerSessions`、core 登录 `authEpoch/scope/operationNo` 与新响应字段。
- 409 传播测试：`GlobalExceptionHandlerTest.staleCoreLogoutConflictRemainsConflict` 先得到 502，预期 409，确认 BFF 原传播缺口。
- 作用域回归测试：非 logout 的 Feign 409 在初版实现中被误改为 409，测试先失败后将透传限定到 `/app/api/v1/auth/logout`。

## GREEN

- 登录直接签 core 返回的 `authEpoch/scope/operationNo`；签发前关闭本节点旧 WS；restricted 响应 TTL 为配置的 1800 秒默认值。
- WS Token 仅接受 Filter 已验证的 `PassengerAuthContext`，保持 epoch、强制 NORMAL/audit=2，不读 Redis。
- logout 从可信 `X-Auth-Epoch` 取 expected epoch，严格执行 core CAS → 本地 WS → 订单清理；stale 409 不触发后续副作用；订单异常返回 `loggedOut=true/orderCleanupPending=true`。
- settings 换号/注销 core 成功后分别以 `phone_changed/account_cancelled` 关闭本地 WS，不再写 Redis token version，也未引入 Saga。
- WS close reason 只允许固定白名单，未知值统一为 `auth_epoch_changed`。
- 删除 `AppJwtService` 四参数过渡签发重载；保留 `PassengerTokenVersionStore` 文件供 Task 8 删除。

## 验证

- 聚焦 Task 5：15 tests，PASS。
- Controller/Filter/WS/Service/settings 组合：27 tests，PASS。
- `mvn -pl passenger-api test`：65 tests，0 failures，0 errors，PASS。
- 静态扫描：认证写路径/WS/settings 无 `PassengerTokenVersionStore`、`nextVersion`、`currentVersion`、`tv`；JWT 仅剩六参数签发；相关生产日志无 Token/手机号输出。
- `git diff --check`：PASS。

## 文件

- 主代码：`AppJwtService`、`AppAuthCustomerBrief`、`GlobalExceptionHandler`、`PassengerAuthController`、`CustomerLoginResponse`、`PassengerLogoutResult`、`PassengerAuthService`、`PassengerSettingsService`、`PassengerWsSessionRegistry`。
- 测试：更新 `PassengerAuthServiceTest`、`PassengerSettingsServiceTest`；新增 `PassengerAuthControllerTest`、`GlobalExceptionHandlerTest`、`PassengerWsSessionRegistryTest`。

## 顾虑

- 当前 WS registry 仍是 brief 明确范围内的单实例内存撤销；多实例全局断连不在本任务范围。
- restricted TTL 由现有 `app.jwt.restricted-expiration-seconds` 配置承载，默认值为 1800 秒。

---

## 评审修复（Critical / Important）

### 1. 订单清理失败语义

**RED**

- 命令：`mvn -pl passenger-api -Dtest=PassengerOrderServiceCancelIdempotencyTest,PassengerAuthServiceTest test`
- 结果：13 tests 中 3 failures。
- 失败分别证明：分页查询非 200 被当作空订单；任一逐单取消失败仍返回 pending=false；BFF 覆盖 helper 的显式 pending 标记。

**GREEN**

- `PassengerOrderService` 对 logout 专用加载返回显式 `complete`，保留其他订单列表调用原有空列表回退语义。
- 查询非 200 立即返回 `orderCleanupPending=true`；逐单取消捕获任意运行时失败、继续尽力处理其他订单并保持 pending=true。
- `PassengerAuthService` 不再把 helper 返回的 pending 强制改为 false；认证 epoch 不做恢复或补偿。
- 同命令：13/13 PASS。

### 2. WS 迟到注册与双注册竞态

**RED**

- 命令：`mvn -pl passenger-api -Dtest=PassengerWsSessionRegistryTest,PassengerWsHandshakeInterceptorTest,PassengerNoticeWebSocketHandlerTest test`
- 结果：testCompile FAIL，缺少 generation permit 捕获/比较 API；现有 handler 只能无条件注册。
- 最终竞态自审另以 `closeImmediatelyAfterRegistrationDoesNotCrashConnectionCallback` 稳定复现：register 返回成功后并发 close 已移除会话，handler 随后 `get(...).touch()` 触发空指针（2 tests 中 1 error）。

**GREEN**

- 握手解析 customerId 后、唯一一次权威 DB 回查前捕获 `RegistrationPermit` 并存入 attributes。
- registry 在同一 customer fence 上串行 register/close；close 先推进 generation，再移除反向映射并关闭会话。
- `捕获 permit → close → afterConnectionEstablished` 会以 `auth_epoch_changed` 拒绝；`register → close` 会移除；并发双注册只保留最后注册者，旧连接和反向映射均清理。
- fence 由握手 permit/活跃会话强持有，registry 只保存弱引用；引用队列由操作路径及 5 秒维护任务回收，避免放弃握手留下永久 customer 状态。
- 每次 WS 握手仍严格只有一次 `authStateClient.get`。
- handler 在 register 成功后重新确认当前会话仍是本次 session；若已被并发 close/replacement 撤销则安静返回，不再访问已移除状态。
- 同命令：11/11 PASS；竞态 handler 聚焦：2/2 PASS。

### 3. core logout 409 调用边界

**RED**

- 命令：`mvn -pl passenger-api -Dtest=PassengerAuthServiceTest,GlobalExceptionHandlerTest test`
- 结果：testCompile FAIL，缺少专用 stale logout 异常和稳定 handler。

**GREEN**

- `PassengerAuthService` 仅在 `authStateClient.logout` 调用边界把 Feign 409 转为 `StalePassengerLogoutException`，发生在 WS close 和订单清理之前。
- `GlobalExceptionHandler` 直接映射专用异常为 409，不再用外层 `requestURI` 判断 Feign 来源。
- 带 contextPath 的 stale logout 稳定返回 409；非 logout 及 logout URI 上未经边界转换的原始 Feign 409 均保持既有 502 语义。
- 同命令：13/13 PASS。

### 评审修复验证

- 新增/变更聚焦：28/28 PASS。
- Task 5 + HTTP Filter + WS 组合：38/38 PASS。
- `mvn -pl passenger-api test`：74 tests，0 failures，0 errors，PASS。
- 静态扫描：认证写路径仍无 Redis token version 依赖；WS 握手仅一次 DB 回查；409 只在 core logout 调用边界判定；相关生产日志无 Token/手机号。
- `git diff --check`：PASS。

### 评审修复顾虑

- generation fence 仍是单实例内存边界；多实例全局断连不属于 Task 5。
- 弱引用 fence 的回收依赖 JVM GC，引用队列由每次 registry 操作及周期维护任务清空，不保留 customer 强引用墓碑。
