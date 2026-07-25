# Task 6 报告：建立乘客账号注销栅栏事务

## Status

PASS

## RED

- 首次聚焦命令：`mvn -pl passenger -Dtest=AccountCancellationFenceServiceIntegrationTest test`
- 结果：FAIL（testCompile），缺少 `LifecycleRequestHasher`、注销建栅栏命令/结果/服务及相关 CAS 方法，证明测试先于实现生效。

## GREEN

- 新增稳定请求哈希：固定包含 `ACCOUNT_CANCEL/customerId/expectedLifecycleVersion/规范化 JSON`，排除 OTP、traceId 与请求时间；JSON 字段顺序和空白不影响哈希。
- 命令允许 `lifecycle_version=0`，仅拒绝负数；校验 idempotencyKey、actorId、请求上下文和请求时间。
- 服务严格执行：请求哈希 → 幂等查重 → 事务外原子消费 OTP → 事务内 Snapshot create → customer CAS → `persistNew` → Operation FENCED CAS → 状态 Event。
- customer CAS 同时写入 `CANCELLING`、`lifecycle_version+1`、`auth_epoch+1` 和当前 operationNo；Operation CAS 同时写入 restricted epoch、applied lifecycle version、fencedAt 与 rowVersion+1。
- 任一 customer/Operation/Event 写入计数异常抛出并回滚；SnapshotStore 原有 Operation/Step/Event/Outbox 计数保护继续生效。OTP 不执行恢复或补偿。
- 未新增 Controller，服务仅作为后续内部编排能力。

## 测试覆盖

- Task 6 集成测试 10 项：稳定哈希、version 0、同键同 hash 重放不再次消费 OTP、同键异 hash 在 OTP 前冲突、完整成功快照、Event 故障回滚、Outbox 故障回滚、Operation CAS 计数异常回滚、双注销竞争、换号 CAS/注销 CAS 跨业务竞争。
- 故障回滚断言覆盖 customer、Operation、Step、Event、Outbox，且 OTP 调用仍为一次。
- 聚焦命令：`mvn -pl passenger -Dtest=AccountCancellationFenceServiceIntegrationTest,LifecycleSnapshotStoreIntegrationTest,LifecycleOperationTransitionServiceTest test`：14 tests，0 failures，0 errors。
- passenger 全量：`mvn -pl passenger test`：128 tests，0 failures，0 errors。
- `git diff --cached --check`：PASS。
- 生命周期注销应用目录 Controller/Mapping 静态扫描：无输出。

## 顾虑

- 并发 CAS 由 H2 MySQL 模式双线程验证，尚未做真实 MySQL 双连接压力测试；SQL 条件与已执行的 MySQL 表结构一致。
- 按用户已确认的 P2 验证边界，本任务不恢复 Docker/Testcontainers；OTP 在集成测试中使用 mock 验证调用次数和事务外不可补偿语义，Redis Lua 原子性由 Task 1 覆盖。

---

## 复审修复

### 事务边界

**RED**

- 从外层 `TransactionTemplate` 调用 `fence()`，断言幂等查重和 `otp.consume` 的事务状态；初版两处均观察到 `TransactionSynchronizationManager.isActualTransactionActive()==true`。

**GREEN**

- `fence()` 入口使用 `PROPAGATION_NOT_SUPPORTED` 的模板显式挂起调用方事务，幂等查重与 OTP 均在无 MySQL 事务环境执行。
- 仅 `createFence` 使用独立 `PROPAGATION_REQUIRED` 模板；回归测试在 Operation CAS 的实际 JDBC 更新处观察到事务状态为 true。
- 该边界由同一个 `PlatformTransactionManager` 的两个编程式模板实现，不依赖 self-invocation 或 AOP 代理碰巧生效。

### 输入约束

- `LifecycleRequestHasher` 开启 `FAIL_ON_TRAILING_TOKENS`，`{} {}` 在 OTP 前被拒绝。
- 命令按表约束校验：`idempotencyKey<=128`、`actorId<=64`、`traceId<=64`；等于上限可构造，超限在 OTP 前失败。

### 复审修复验证

- Task 6 集成测试增至 13 项。
- Task 6 + 关联聚焦：17 tests，0 failures，0 errors。
- passenger 全量：131 tests，0 failures，0 errors。
- `git diff --check`：PASS；生命周期注销应用目录 Controller/Mapping 扫描无输出。
