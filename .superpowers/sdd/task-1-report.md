# Task 1 报告：按用途隔离且一次性消费的 OTP

## 状态

**完成。** 用户已明确覆盖原 Docker/Testcontainers 验证计划：本阶段不引入、不运行真实 Redis 多线程并发测试，也不以 `@Disabled` 或 mock 冒充该验证。该风险已延期记录；Lua 单命令契约、旧入口迁移和 passenger 全量测试均通过。

## 实现范围

- 新增 `auth/otp`：只含 `LOGIN`、`PHONE_CHANGE_NEW_PHONE`、`ACCOUNT_CANCEL` 的 `OtpPurpose`，以及 `OtpSubject`、`OtpKeyFactory`、`OtpConsumeResult`、`AtomicOtpService`。
- `OtpKeyFactory` 生成精确 v2 key，拒绝用途不匹配、空手机号、非正 customerId、空值或负数 lifecycleVersion，以及注销用途携带手机号的 subject。
- `AtomicOtpService.consume` 通过一个 key 的 Lua `GET`/比较/`DEL` 返回 `MISSING`、`MISMATCH`、`CONSUMED`；没有公开 `get`、`delete` 或恢复 OTP 的接口。
- 登录、换号、注销均迁移到相应 purpose；换号/注销由已加载的 `Customer.lifecycleVersion` 构造 subject。发送频控 gap/daily key 和计数规则保持不变。
- OTP 消费发生在数据库写入前；数据库失败时不会恢复 OTP。认证/设置日志不再记录 OTP 或手机号明文。
- 按用户覆盖删除 Testcontainers 依赖、import 和真实容器并发测试；正式计划同步记录该延期。

## RED → GREEN 记录

### 1. Key/用途契约

RED：

```bash
mvn -pl passenger -Dtest=OtpKeyFactoryTest test
```

预期编译失败，报告 `OtpKeyFactory`（以及尚未创建的 OTP 类型）找不到，证明测试在功能缺失时失败。

GREEN：同一命令在实现 `OtpPurpose`、`OtpSubject`、`OtpKeyFactory` 后通过：`Tests run: 2, Failures: 0, Errors: 0`。

### 2. Lua 原子消费

RED：

```bash
mvn -pl passenger -Dtest=AtomicOtpServiceTest test
```

预期编译失败，报告 `AtomicOtpService` 和 `OtpConsumeResult` 找不到。

GREEN：

```bash
mvn -pl passenger -Dtest=AtomicOtpServiceTest test
```

通过：`Tests run: 1, Failures: 0, Errors: 0`。测试断言单次 `execute` 的 Lua `0/1/2` 返回值分别映射为 `MISSING/MISMATCH/CONSUMED`，并验证不调用第二条 Redis `delete` 命令。

原计划的 `GenericContainer<>("redis:7.2-alpine")`、20 线程真实 Redis 并发断言曾因 `/var/run/docker.sock` 缺失无法执行。用户随后明确决定跳过该计划；相关依赖和测试现已删除，未被禁用、未被 mock 替代。真实 Redis 多线程验证延期为剩余风险。

### 3. Auth/Settings 迁移

RED：先修改/新增 `AppCustomerAuthServiceTest` 与 `AppCustomerSettingsServiceTest` 后执行：

```bash
mvn -pl passenger -Dtest=AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test
```

预期失败：两个服务构造器尚未接收 `AtomicOtpService`。

GREEN：同一命令在迁移后通过：`Tests run: 9, Failures: 0, Errors: 0`。覆盖 LOGIN 存储、`MISSING/MISMATCH` 同样返回 401、换号/注销 purpose + lifecycleVersion，以及消费后数据库失败不恢复 OTP。

应用上下文曾暴露 `OtpKeyFactory` 未注册为 Spring bean；根因是 `AtomicOtpService` 构造器依赖该工厂。最小修复为 `@Component`，并通过：

```bash
mvn -pl passenger -Dtest=PassengerSpringApplicationTests test
```

结果：`Tests run: 1, Failures: 0, Errors: 0`。

## 最终 GREEN 验证

聚焦测试：

```bash
mvn -pl passenger -Dtest=OtpKeyFactoryTest,AtomicOtpServiceTest,AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test
```

结果：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`。

完整 passenger 测试：

```bash
mvn -pl passenger test
```

结果：`Tests run: 74, Failures: 0, Errors: 0, Skipped: 0`。

## 修改文件

- `docs/superpowers/plans/2026-07-21-乘客认证代次原子OTP与受限会话.md`
- `passenger/src/main/java/com/sx/passenger/auth/otp/OtpPurpose.java`
- `passenger/src/main/java/com/sx/passenger/auth/otp/OtpConsumeResult.java`
- `passenger/src/main/java/com/sx/passenger/auth/otp/OtpSubject.java`
- `passenger/src/main/java/com/sx/passenger/auth/otp/OtpKeyFactory.java`
- `passenger/src/main/java/com/sx/passenger/auth/otp/AtomicOtpService.java`
- `passenger/src/main/java/com/sx/passenger/app/AppCustomerAuthService.java`
- `passenger/src/main/java/com/sx/passenger/app/AppCustomerSettingsService.java`
- `passenger/src/test/java/com/sx/passenger/auth/otp/OtpKeyFactoryTest.java`
- `passenger/src/test/java/com/sx/passenger/auth/otp/AtomicOtpServiceTest.java`
- `passenger/src/test/java/com/sx/passenger/app/AppCustomerAuthServiceTest.java`
- `passenger/src/test/java/com/sx/passenger/app/AppCustomerSettingsServiceTest.java`

## 自审与顾虑

- `git diff --check` 通过。
- 静态检索确认没有 Testcontainers、`GenericContainer`、`@Testcontainers`；认证/设置中不存在旧 OTP key 或 OTP 成功路径 `opsForValue().get(...)` 后 `redis.delete(...)`。剩余 `redis.delete` 只删除发送频控 gap key，剩余 `get` 只读取登录封禁标记。
- 没有新增公开 lifecycle 接口，settings 也未迁移 Saga；未触碰 `.superpowers/dataless-backup`。
- 剩余风险：单条 Lua 的真实 Redis 多线程行为尚未以容器化集成测试复验，按用户明确覆盖延期。换号/注销要求历史 customer 的 `lifecycleVersion` 非空且非负；应确认 P1 的初始化/数据迁移满足该约束。

## 修复：允许初始生命周期版本 0

评审确认 `lifecycle_version=0` 是合法初始版本；仅 `null` 或负数非法，`customerId` 仍必须大于 0。没有修改 SQL 默认值。

RED：先新增 `OtpKeyFactoryTest` 对 `PHONE_CHANGE_NEW_PHONE`、`ACCOUNT_CANCEL` 的 version `0` key 契约，以及 `AppCustomerSettingsServiceTest` 的 version `0` 换号发送/确认链路，后执行：

```bash
mvn -pl passenger -Dtest=OtpKeyFactoryTest,AppCustomerSettingsServiceTest test
```

预期失败，结果为 3 个 error：`OtpKeyFactory.requiredVersion` 抛出 `OTP subject requires positive lifecycleVersion`，且 settings 的 `lifecycleVersion` 在发送与确认处抛出 `Customer lifecycleVersion is required for OTP`。这证明 `<= 0` 边界确实拒绝初始版本。

GREEN：将上述两个校验从 `<= 0` 收窄为 `< 0`，并将正式 P2 计划的“非正 version”改为“负数 version”。同一覆盖命令通过：`Tests run: 11, Failures: 0, Errors: 0`。

后续验证：

```bash
mvn -pl passenger -Dtest=OtpKeyFactoryTest,AtomicOtpServiceTest,AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test
mvn -pl passenger test
```

结果分别为 `15/15` 与 `77/77` 通过；`git diff --check` 通过。
