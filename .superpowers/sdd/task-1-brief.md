### Task 1:建立按用途隔离且一次性消费的 OTP 组件

**文件：**
- 新建：`passenger/src/main/java/com/sx/passenger/auth/otp/OtpPurpose.java`
- 新建：`passenger/src/main/java/com/sx/passenger/auth/otp/OtpConsumeResult.java`
- 新建：`passenger/src/main/java/com/sx/passenger/auth/otp/OtpSubject.java`
- 新建：`passenger/src/main/java/com/sx/passenger/auth/otp/OtpKeyFactory.java`
- 新建：`passenger/src/main/java/com/sx/passenger/auth/otp/AtomicOtpService.java`
- 新建：`passenger/src/test/java/com/sx/passenger/auth/otp/OtpKeyFactoryTest.java`
- 新建：`passenger/src/test/java/com/sx/passenger/auth/otp/AtomicOtpServiceTest.java`
- 修改：`passenger/src/main/java/com/sx/passenger/app/AppCustomerAuthService.java`
- 修改：`passenger/src/main/java/com/sx/passenger/app/AppCustomerSettingsService.java`
- 修改：`passenger/src/test/java/com/sx/passenger/app/AppCustomerSettingsServiceTest.java`
- 新建：`passenger/src/test/java/com/sx/passenger/app/AppCustomerAuthServiceTest.java`

**接口：**
- 使用：`StringRedisTemplate.execute(DefaultRedisScript<Long>, List<String>, Object...)`。
- 产出：`String OtpKeyFactory.key(OtpPurpose purpose, OtpSubject subject)`；`OtpConsumeResult AtomicOtpService.consume(OtpPurpose purpose, OtpSubject subject, String submittedCode)`；`void AtomicOtpService.store(..., String code, Duration ttl)`。

- [ ] **步骤 1：写 Key/用途契约失败测试**

```java
class OtpKeyFactoryTest {
    private final OtpKeyFactory keys = new OtpKeyFactory();

    @Test void buildsPurposeIsolatedKeys() {
        assertThat(keys.key(OtpPurpose.LOGIN, OtpSubject.login("13800138000")))
                .isEqualTo("app:otp:v2:LOGIN:13800138000");
        assertThat(keys.key(OtpPurpose.PHONE_CHANGE_NEW_PHONE,
                OtpSubject.phoneChange(7L, "13900139000", 12L)))
                .isEqualTo("app:otp:v2:PHONE_CHANGE_NEW_PHONE:7:13900139000:12");
        assertThat(keys.key(OtpPurpose.ACCOUNT_CANCEL, OtpSubject.accountCancel(7L, 12L)))
                .isEqualTo("app:otp:v2:ACCOUNT_CANCEL:7:12");
    }

    @Test void rejectsSubjectThatDoesNotMatchPurpose() {
        assertThatThrownBy(() -> keys.key(OtpPurpose.LOGIN, OtpSubject.accountCancel(7L, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`mvn -pl passenger -Dtest=OtpKeyFactoryTest test`

预期：FAIL，编译器报告 `OtpPurpose/OtpSubject/OtpKeyFactory` 不存在。

- [ ] **步骤 3：实现不可混用的 Key 类型**

```java
public enum OtpPurpose { LOGIN, PHONE_CHANGE_NEW_PHONE, ACCOUNT_CANCEL }

public record OtpSubject(String phone, Long customerId, Long lifecycleVersion) {
    public static OtpSubject login(String phone) { return new OtpSubject(phone, null, null); }
    public static OtpSubject phoneChange(long id, String phone, long version) {
        return new OtpSubject(phone, id, version);
    }
    public static OtpSubject accountCancel(long id, long version) {
        return new OtpSubject(null, id, version);
    }
}

public final class OtpKeyFactory {
    public String key(OtpPurpose purpose, OtpSubject subject) {
        return switch (purpose) {
            case LOGIN -> "app:otp:v2:LOGIN:" + requiredPhoneOnly(subject);
            case PHONE_CHANGE_NEW_PHONE -> "app:otp:v2:PHONE_CHANGE_NEW_PHONE:"
                    + requiredId(subject) + ":" + requiredPhone(subject) + ":" + requiredVersion(subject);
            case ACCOUNT_CANCEL -> "app:otp:v2:ACCOUNT_CANCEL:"
                    + requiredIdWithoutPhone(subject) + ":" + requiredVersion(subject);
        };
    }
    // required* 方法必须拒绝空手机号、非正 customerId、负数 version 和多余字段。
}
```

- [ ] **步骤 4：写 Lua 三态与并发单次消费失败测试**

```java
@ExtendWith(MockitoExtension.class)
class AtomicOtpServiceTest {
    @Mock StringRedisTemplate redis;
    private AtomicOtpService service;

    @BeforeEach void setUp() { service = new AtomicOtpService(redis, new OtpKeyFactory()); }

    @Test void mapsLuaReturnCodesWithoutSecondRedisCommand() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), eq("111111")))
                .thenReturn(0L, 1L, 2L);
        OtpSubject s = OtpSubject.login("13800138000");
        assertThat(service.consume(OtpPurpose.LOGIN, s, "111111")).isEqualTo(OtpConsumeResult.MISSING);
        assertThat(service.consume(OtpPurpose.LOGIN, s, "111111")).isEqualTo(OtpConsumeResult.MISMATCH);
        assertThat(service.consume(OtpPurpose.LOGIN, s, "111111")).isEqualTo(OtpConsumeResult.CONSUMED);
        verify(redis, never()).delete(anyString());
    }
}
```

本阶段按项目环境约束不引入或运行 Docker/Testcontainers 真实 Redis 并发用例。原子性由单条 Lua 脚本、`0/1/2` 返回码映射以及“消费成功路径不再执行第二条 `delete` 命令”的单元契约保证；真实 Redis 多线程并发验证延期执行，并作为剩余风险记录，不得用 mock 宣称已完成真实并发验证。

- [ ] **步骤 5：实现单 Key Lua 消费**

```java
public enum OtpConsumeResult { MISSING, MISMATCH, CONSUMED }

private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[1])
        if not current then return 0 end
        if current ~= ARGV[1] then return 1 end
        redis.call('DEL', KEYS[1])
        return 2
        """, Long.class);

public OtpConsumeResult consume(OtpPurpose purpose, OtpSubject subject, String submittedCode) {
    if (submittedCode == null || submittedCode.isBlank()) return OtpConsumeResult.MISMATCH;
    Long result = redis.execute(CONSUME, List.of(keys.key(purpose, subject)), submittedCode.trim());
    if (result == null) throw new IllegalStateException("OTP store unavailable");
    return switch (result.intValue()) {
        case 0 -> OtpConsumeResult.MISSING;
        case 1 -> OtpConsumeResult.MISMATCH;
        case 2 -> OtpConsumeResult.CONSUMED;
        default -> throw new IllegalStateException("Unknown OTP consume result: " + result);
    };
}
```

`store` 只能使用 `OtpKeyFactory` 生成 Key，并保留现有 TTL；不得提供 `get`、`delete` 或“恢复验证码”API。

- [ ] **步骤 6：先用失败测试锁定旧入口迁移行为**

测试以下行为：LOGIN 发送/登录只使用 `LOGIN`；换号发送时读取当前 `lifecycleVersion` 并使用 `PHONE_CHANGE_NEW_PHONE`；注销同理；`MISSING/MISMATCH` 均返回相同 401；`CONSUMED` 后数据库失败不调用 `store` 恢复；源码中不再出现 OTP 成功路径的 `opsForValue().get(...)` 后 `redis.delete(...)`。

- [ ] **步骤 7：将 AppCustomerAuthService/AppCustomerSettingsService 注入 AtomicOtpService**

```java
if (otpService.consume(OtpPurpose.LOGIN, OtpSubject.login(req.getPhone()), req.getCode())
        != OtpConsumeResult.CONSUMED) {
    recordLoginFail(req.getPhone());
    return unauthorizedSms();
}
```

换号/注销从已加载的 `Customer.getLifecycleVersion()` 构造 subject；发送频控沿用原 Key 和计数规则，仅 OTP 存储 Key 改为 v2。

- [ ] **步骤 8：运行 passenger 认证/设置测试确认 GREEN**

运行：`mvn -pl passenger -Dtest=OtpKeyFactoryTest,AtomicOtpServiceTest,AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test`

预期：PASS；Lua 返回码、单命令消费和三个用途的旧入口迁移契约全部通过。真实 Redis 多线程并发验证不属于本阶段门禁。

- [ ] **步骤 9：提交任务 1**

```bash
git add passenger/src/main passenger/src/test
git commit -m "功能：统一乘客原子验证码消费"
```

