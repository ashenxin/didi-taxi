# Task 7 实施报告：同 customer.id 换号事务

## 实施结果

- 新增 `CustomerPhoneChangeService` 内部应用服务，无 Controller。
- 入口使用 `PROPAGATION_NOT_SUPPORTED` 挂起调用方事务；幂等查询、customer 前置校验、手机号占用查询和 OTP 消费均在事务外；数据库阶段使用独立 `PROPAGATION_REQUIRED`。
- 请求顺序固定为：请求哈希 → 幂等 → customer/新旧手机号校验 → 原子 OTP → DB 事务。
- DB 事务顺序固定为：创建运行快照 → 同一 customer 行换号 CAS → ACTIVE 绑定改 REPLACED → 新 ACTIVE 绑定 → `persistNew` → Operation `REQUESTED→EXECUTING→COMPLETED` → Step `PENDING→RUNNING→SUCCEEDED` → 状态 Event → 完成 Outbox。
- customer.id 不变；只更新同一行的 phone/lifecycle_version/auth_epoch，不新建 customer，也不迁移订单、钱包、券或积分。
- `lifecycle_version=0` 可用；OTP subject 显式绑定 customerId、新手机号和 expected lifecycle version。
- 完成态 Operation 保存 `applied_lifecycle_version`、`completed_at`，并在 `restricted_auth_epoch` 固化本次换号产生的新 auth epoch，保证幂等重放返回稳定结果。
- 新手机号唯一键竞争统一转为 `LifecycleOperationConflictException`（领域 409）。
- 绑定历史按当前已确认契约保留：旧 ACTIVE→REPLACED，新记录使用 max(binding_version)+1 并关联 operationNo。

## TDD 证据

RED：

```text
mvn -pl passenger -Dtest=CustomerPhoneChangeServiceIntegrationTest test
BUILD FAILURE
CustomerPhoneChangeService / ChangeCustomerPhoneCommand 不存在，测试编译按预期失败。
```

GREEN（聚焦）：

```text
mvn -pl passenger -Dtest=CustomerPhoneChangeServiceIntegrationTest,AccountCancellationFenceServiceIntegrationTest,LifecycleSnapshotStoreIntegrationTest test
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

全量：

```text
mvn -pl passenger test
Tests run: 144, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Task 7 自身共 13 个集成测试，覆盖身份保持、绑定历史、完成态运行快照、幂等、哈希稳定性、JSON/字段长度、新旧手机号校验、手机号占用、初始版本 0、真实外层事务挂起、唯一冲突、数据库全回滚、OTP 不恢复，以及换号/注销版本竞争。

## 静态检查

- `git diff --check`：无输出。
- 换号应用包未出现 customer insert/new Customer 或订单、钱包、券、积分迁移调用。
- Event/Outbox payload 仅包含 eventId、operationNo、operationType、customerId、版本、authEpoch、occurredAt，不包含手机号或 OTP。

## 已知边界与风险

- 依照本轮已确认的“明文问题暂不处理、绑定历史表维持现状”，`PhoneBindingValueFactory` 暂以 UTF-8 字节写入 `phone_ciphertext`，身份摘要使用稳定 SHA-256 并标记 `legacy-v1`；没有伪造 KMS/HMAC 密钥。后续密钥体系定版时应只替换该工厂，并对历史数据做版本化回填。
- 并发验证使用 H2 的真实双线程事务与数据库唯一约束；未补真实 MySQL 压测。
- 本 Task 只提供内部 Application Service，旧 settings 接口的转调由后续 Task 负责。

## 复审修正

- 复审发现初版在整个数据库事务外捕获 `DuplicateKeyException`，会把绑定历史、Operation、Event、Outbox 等无关唯一冲突误报成手机号占用。
- 修正后捕获严格位于 `customers.changePhoneCas(...)` 边界；只有异常链、JDBC next exception、可用 constraint metadata 或异常消息中精确出现 `uk_customer_phone_active` 标识时才转换为手机号占用领域冲突。
- H2 使用真实竞争数据验证约束识别；另用 MySQL 1062/23000 异常链验证生产消息形态。customer CAS 的未知唯一冲突和 CAS 后绑定历史唯一冲突均保持原异常传播，且数据库事务整体回滚。
- 复审修正 RED：无关 history DuplicateKey 被错误转成 `LifecycleOperationConflictException`，1 个用例按预期失败。
- 修正后聚焦测试 31/31；passenger 全量测试 147/147。
