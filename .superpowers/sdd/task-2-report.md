# Task 2 实施报告：在 passenger 建立数据库权威认证代次与会话材料

## 结论

- 状态：实现、验证与提交完成。
- 基线：`730b6c07fe3c0cd7653e42d2ecebd25596316919`。
- 范围：仅修改 `passenger` 内部服务、DTO、mapper 与测试；未新增或修改公开 Controller，旧 settings 仍为即时换号/即时注销，不创建 Lifecycle Operation。
- 权威性：`customer.auth_epoch` 仅通过数据库原子递增或新用户显式初始化为 `0`；没有恢复或降低 epoch 的路径。

## 实现摘要

1. 新增 `PassengerAuthEpochService`，提供：
   - `completeAuthentication(long)`：ACTIVE 签发 NORMAL 材料；CANCELLING 签发绑定当前非终态 Operation 的 LIFECYCLE_RESTRICTED 材料，并在同一事务更新 `restricted_auth_epoch`；其余状态拒绝。
   - `logout(long,long)`：按 expected epoch 做 CAS，旧会话登出不能使新会话失效。
   - `loadState(long)`：权威读取 customer 与绑定 Operation；不存在、删除、冻结、CANCELLED、缺失/终态/未知 Operation 状态均 fail-closed。
2. 登录密码校验或 LOGIN OTP 成功后统一调用数据库 epoch bump；新注册显式写入 `ACTIVE / lifecycleVersion=0 / authEpoch=0`。
3. 旧换号与注销分别迁到单条 customer CAS UPDATE：
   - 换号：同条 SQL 更新 phone，并递增 `auth_epoch` 与 `lifecycle_version`。
   - 注销：同条 SQL 设置删除、CANCELLED、cancelled_at，并递增 `auth_epoch` 与 `lifecycle_version`。
   - 两者都要求 expected `lifecycle_version`、`lifecycle_status='ACTIVE'`、`is_deleted=0`，CAS 失败返回 409。
4. 日志仍只输出掩码手机号；未记录 OTP、Token 或手机号原文。

## RED / GREEN 记录

### Round 1：认证代次服务基本状态与 stale logout

- RED 命令：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest test`
- RED 结果：exit 1；测试编译失败，关键输出为 `找不到符号: 类 PassengerAuthEpochService`，符合功能尚不存在的预期。
- GREEN 命令：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest test`
- GREEN 结果：exit 0；`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。

### Round 2：登录调用点与 settings 专用 CAS

- RED 命令：`mvn -pl passenger -Dtest=AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test`
- 首次 RED 结果：exit 1；除预期的构造器尚未接入 `PassengerAuthEpochService` 外，测试 matcher 还触发了 BaseMapper 重载歧义。
- 测试修正：为 `argThat` 显式指定 `Customer` 类型；未改生产代码。
- RED 复跑：同一命令 exit 1；仅剩预期失败：`AppCustomerAuthService` 构造器参数列表缺少 `PassengerAuthEpochService`。
- GREEN 命令：同一命令。
- GREEN 结果：exit 0；`Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`。

### Round 3：换号/注销单 SQL 原子性

- RED 命令：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest test`
- RED 结果：exit 1；`Tests run: 6, Errors: 2`，关键输出为 MyBatis 找不到 `changePhoneCas` 与 `cancelAccountCas` bound statement。
- GREEN 命令：同一命令。
- GREEN 结果：exit 0；`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`；验证字段更新、epoch/version 同步递增以及旧 version CAS 为 0。

### Round 4：CANCELLING 必须绑定非终态 Operation

- RED 命令：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest#loadStateRejectsCancellingCustomerWhenBoundOperationIsTerminal test`
- RED 结果：exit 1；`Tests run: 1, Failures: 1`，关键输出 `Expecting value to be false but was true`。
- GREEN 命令：同一命令。
- GREEN 结果：exit 0；`Tests run: 1, Failures: 0, Errors: 0`。

### Round 5：认证事务失败回滚验证夹具修正

- 初始聚焦命令：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest,AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test`
- 结果：`Tests run: 25, Failures: 1`；失败断言在类级测试事务内仍看见未结束事务中的 epoch=6。该事务已标记回滚，问题属于测试可见性而非生产提交。
- 修正：仅该用例使用 `Propagation.NOT_SUPPORTED` 移除外层测试事务，并在 `@AfterEach` 清理夹具，使 `PassengerAuthEpochService` 建立并回滚真实事务。
- 修正验证命令：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest#cancellingAuthenticationRollsBackEpochWhenOperationUpdateConflicts test`
- 结果：exit 0；`Tests run: 1, Failures: 0, Errors: 0`，回滚后 epoch 保持 5。
- 聚焦复跑：同一组三类测试命令 exit 0；当时 `Tests run: 25, Failures: 0, Errors: 0`。

### Round 6：未知 Operation 状态 fail-closed

- RED 命令：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest#loadStateRejectsCancellingCustomerWhenBoundOperationStatusIsUnknown test`
- RED 结果：exit 1；`Tests run: 1, Failures: 1`，关键输出 `Expecting value to be false but was true`。
- GREEN 命令：同一命令。
- GREEN 结果：exit 0；`Tests run: 1, Failures: 0, Errors: 0`；实现改为只允许明确列出的七种非终态。

## 最终验证

- 聚焦：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest,AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test`
  - exit 0；`Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`。
- 全模块：`mvn -pl passenger test`
  - exit 0；`Tests run: 92, Failures: 0, Errors: 0, Skipped: 0`。
- 格式：`git diff --check`
  - exit 0，无输出。

## 文件清单

新增：

- `passenger/src/main/java/com/sx/passenger/auth/session/AuthSessionScope.java`
- `passenger/src/main/java/com/sx/passenger/auth/session/AuthoritativeAuthState.java`
- `passenger/src/main/java/com/sx/passenger/auth/session/AuthStateRejectedException.java`
- `passenger/src/main/java/com/sx/passenger/auth/session/AuthEpochConflictException.java`
- `passenger/src/main/java/com/sx/passenger/auth/session/PassengerAuthEpochService.java`
- `passenger/src/test/java/com/sx/passenger/auth/session/PassengerAuthEpochServiceIntegrationTest.java`

修改：

- `passenger/src/main/java/com/sx/passenger/dao/CustomerEntityMapper.java`
- `passenger/src/main/resources/mapper/CustomerEntityMapper.xml`
- `passenger/src/main/java/com/sx/passenger/lifecycle/persistence/mapper/LifecycleOperationMapper.java`
- `passenger/src/main/resources/mapper/lifecycle/LifecycleOperationMapper.xml`
- `passenger/src/main/java/com/sx/passenger/app/dto/AppAuthCustomerBrief.java`
- `passenger/src/main/java/com/sx/passenger/app/AppCustomerAuthService.java`
- `passenger/src/test/java/com/sx/passenger/app/AppCustomerAuthServiceTest.java`
- `passenger/src/main/java/com/sx/passenger/app/AppCustomerSettingsService.java`
- `passenger/src/test/java/com/sx/passenger/app/AppCustomerSettingsServiceTest.java`

## 静态自审

- 数据权威：所有现有用户 epoch 变化均为 `auth_epoch = auth_epoch + 1`；唯一 `setAuthEpoch(0L)` 仅用于新用户初始化。
- 并发：登录 UPDATE 对同一 customer 行加锁并在事务内读取新值；两次成功认证得到不同 epoch。logout 带 expected epoch，stale logout 更新数为 0。
- 生命周期：认证 bump 只允许业务状态正常且生命周期为 ACTIVE/CANCELLING；CANCELLING 更新 Operation 失败会抛异常并回滚 customer bump。
- settings：换号/注销保持同一 `customer.id`，使用 version + ACTIVE 条件 CAS；未提前创建 Saga Operation。
- 拒绝状态：不存在、删除、冻结、CANCELLED、缺失 Operation、终态 Operation、未知 Operation 状态均不放行。
- 安全日志：新增/修改日志只使用 `maskPhone`；没有 OTP、Token、手机号原文输出。
- 接口面：没有 Controller 文件变更，没有新增公开 HTTP 接口。
- 差异卫生：`git diff --check` 通过；未触碰 brief/report 以外的 `.superpowers` 用户文件。

## 顾虑与已知限制

- 集成测试使用 H2 MySQL mode；未在真实 MySQL 上执行双线程并发压力测试。实现依赖标准行级 UPDATE 锁和 CAS 条件，聚焦测试覆盖连续认证不同 epoch 与 stale logout，但真实数据库并发压测可在后续环境验收补充。
- 全量测试仍输出仓库既有的 MyBatis-Plus 无主键警告（`SysUserRole`、`SysRoleMenu`）及 Byte Buddy 动态 agent 警告；本任务未引入这些警告，且测试无失败/错误。
- 按已确认范围，真实 Redis 多线程验证继续延期，本任务没有恢复 Redis token version 权威。

## 提交

- Commit：`0eb8ee6`（完整值以 `git rev-parse HEAD` 为准）
- Message：`功能：以数据库认证代次签发会话材料`
