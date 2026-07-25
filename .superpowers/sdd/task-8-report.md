# Task 8 实施报告

## 结果摘要

- 完成 passenger 与 passenger-api 的 P2 切换配置，JWT audience 固定为 `app-bff`，普通/受限 TTL 分别由统一环境变量控制。
- 两端启动校验仅对“非空且全部为 local/dev/test”的 profile 集合放宽；混合 profile 和空 profile 严格校验，并拒绝 Token/密钥首尾空白。
- 新增两端 `PassengerAuthMetrics`，7 个指标均使用 enum/switch 产生固定低基数 Tag，并接入 OTP、epoch、生命周期 CAS、HTTP/WS 权威查询、JWT 拒绝、受限签发和本节点 WS 关闭调用点。
- epoch 指标在 CAS 命中后挂到事务同步回调：提交后才记 success，回滚记 failure；WS closed 只在已打开连接实际关闭成功时计数。
- HTTP/WS 的 JWT 拒绝指标已区分 `epoch_mismatch` 与其他 `state_mismatch`；WS 下游 5xx 保持 503 失败关闭。
- 两端指标写入均收口为 best-effort；Registry/计时/事务同步异常不会传播到认证、登出、换号、注销或 HTTP/WS 裁决。
- passenger 主代码硬性禁止 NESTED 事务传播，由根目录/模块目录均可定位源码的架构测试持续守卫。
- 删除 `PassengerTokenVersionStore`，同时移除 passenger-api 的 Redis starter 和 Redis 配置；HTTP/WS 认证链路无 Redis 类型依赖。
- 新增单一契约源 `docs/superpowers/contracts/passenger-auth-state-v1.json`；passenger Controller 测试与 passenger-api DTO/裁决测试均通过 Maven test-resource 从同一文件加载。
- 设计文档新增中文“P2 实现结果与上线检查单”，明确部署顺序、观察项和禁止降低 `auth_epoch`/恢复旧 Token/恢复 Redis 权威的回滚边界。
- 旧 settings 编排保持不变；未新增 lifecycle Controller；方案 A 的 `lifecycleVersion=0` 规则未改变。

## TDD 证据

RED：

- `mvn -pl passenger-api -Dtest=PassengerSecurityStartupValidatorTest,PassengerAuthMetricsTest,PassengerAuthStateContractTest test`
- 预期失败：`validateStrict` 尚无内部 Token 参数，`PassengerAuthMetrics` 尚不存在。
- `mvn -pl passenger -Dtest=PassengerAuthMetricsTest,PassengerInternalAuthControllerTest test`
- 预期失败：passenger 侧 `PassengerAuthMetrics` 尚不存在。

GREEN：

- 审查整改 passenger focused：启动校验、epoch 指标、认证服务、settings、换号/注销事务全部通过。
- 审查整改 passenger-api focused：启动校验、HTTP JWT、WS 握手与会话注册表全部通过。
- `mvn -pl passenger verify`：157 项，BUILD SUCCESS，JaCoCo 检查通过。
- `mvn -pl passenger-api verify`：87 项，BUILD SUCCESS，JaCoCo 检查通过。
- `mvn -pl passenger,passenger-api -am verify`：父工程、passenger、passenger-api 全部 BUILD SUCCESS；157 + 87 项测试再次通过。
- 按用户明确要求，未执行 Docker/Testcontainers 验证。

## 静态扫描

- P2 乘客认证范围内 `PassengerTokenVersionStore/passenger:tv:/JWT tv/tokenVersion`：无输出。
- passenger 全量扫描仍有 3 处后台管理员会话字段 `tokenVersion`：`SysUser`、`AdminSecurityContextResponse`、`AdminVerifyCredentialsResponse`；它们属于后台管理员认证，未参与乘客 P2，故保留现状。
- passenger-api 认证与 WS 握手源码中的 `Redis/redis`：无输出。
- passenger 旧 OTP 成功路径扫描：无输出。
- lifecycle 公开 Controller 映射扫描：无输出。
- `PASSENGER_INTERNAL_TOKEN=...` 硬编码扫描：无输出；仅 YAML 环境变量占位和开发默认值存在。
- `git diff --check`：无输出。
- `Propagation.NESTED/PROPAGATION_NESTED` 主代码扫描：无输出；架构测试从 Reactor 根目录和 passenger 模块目录分别执行通过。

## 剩余风险

- 用户已明确跳过 Docker/Testcontainers，因此 Task 1 的真实 Redis 多线程 Lua 并发验证仍延期；本次只依赖单 Lua 命令与单元契约。
- P2 只关闭当前 passenger-api 实例的 WS；跨实例可靠撤销仍属于 P6。
- 权威状态查询指标位于 BFF 调 passenger 的调用边界，能观察端到端延迟与可用性；数据库内部 SQL 分段耗时需由 passenger/数据库监控补充。
- 生命周期事务中的 Micrometer 指标是进程内 best-effort 可观测信号，不参与事务正确性和认证裁决。

## 最终审查修复（2026-07-22）

### 修复内容

- `PassengerJwtAuthFilter` 的过滤范围、公开认证与受限路径统一基于
  `ServletRequestPathUtils.pathWithinApplication + PathPattern`；actuator、内部通知和 WS 使用精确命名空间
  Pattern 放行，其余路径默认 fail-closed。context path、matrix 参数、percent/double-encoded 路径不再绕过
  JWT 与 DB 权威状态回查，公开登录只精确匹配三个 POST 路径。
- passenger 旧 settings 的换号/注销成功 DTO 兼容新增
  `customerId/newAuthEpoch/requireLogin/revocationReason`。`newAuthEpoch` 不再由事务前快照加一推算，CAS 成功后
  在同一事务中按 customer.id 回读数据库权威值；注销后的逻辑删除行仍可读取。
- passenger-api 新增未公开、可直接单测的 `PassengerLifecycleOrchestrator`。现有 settings 复用该唯一边界；
  只有核心 200、操作完成且撤销事实完整一致时才推进本节点 WS generation 栅栏并关闭旧会话，核心失败、
  不完整响应或 operation=false 均不关闭。未新增 Controller 或伪造网络层实现。
- 设计与实施文档明确：未来公开换号/注销入口必须复用该编排器；P2 仍仅保证本实例，跨实例可靠撤销留给 P6。

### TDD 证据

RED：

- `mvn -pl passenger-api -Dtest=PassengerJwtAuthFilterTest,PassengerLifecycleOrchestratorTest,PassengerSettingsServiceTest test`
  首次编译暴露旧 settings 测试尚未迁移；迁移后 16 项中 3 项按预期失败：context path 绕过、matrix public
  被误拒、encoded/double-encoded 未查 DB。
- 新增“核心 200 但 changed=false 不关闭会话”后，`PassengerLifecycleOrchestratorTest` 5 项中 1 项按预期失败。
- 新增“返回数据库权威 newAuthEpoch”后，passenger focused 因 mapper 查询尚不存在按预期编译失败。

GREEN：

- passenger-api 路径与生命周期 focused：16/16；生命周期语义校验：5/5。
- passenger 核心 settings focused：11/11。
- `mvn -pl passenger verify`：158 项，BUILD SUCCESS。
- `mvn -pl passenger-api verify`：96 项，BUILD SUCCESS。
- `mvn -pl passenger,passenger-api -am verify`：父工程、passenger、passenger-api 全部 BUILD SUCCESS；
  158 + 96 项测试再次通过。
- 按用户要求未运行 Docker/Testcontainers。
