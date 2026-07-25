### Task 3:保护 passenger 内部入口并提供权威认证状态/登出接口

**文件：**
- 新建： `passenger/src/main/java/com/sx/passenger/internal/security/PassengerInternalAuthProperties.java`
- 新建： `passenger/src/main/java/com/sx/passenger/internal/security/PassengerInternalAuthFilter.java`
- 新建： `passenger/src/main/java/com/sx/passenger/internal/security/PassengerInternalSecurityStartupValidator.java`
- 新建： `passenger/src/main/java/com/sx/passenger/internal/auth/PassengerInternalAuthController.java`
- 新建： `passenger/src/main/java/com/sx/passenger/internal/auth/dto/InternalAuthStateResponse.java`
- 新建： `passenger/src/main/java/com/sx/passenger/internal/auth/dto/InternalLogoutRequest.java`
- 新建： `passenger/src/main/java/com/sx/passenger/internal/auth/dto/InternalLogoutResponse.java`
- 新建： `passenger/src/test/java/com/sx/passenger/internal/security/PassengerInternalAuthFilterTest.java`
- 新建： `passenger/src/test/java/com/sx/passenger/internal/security/PassengerInternalSecurityStartupValidatorTest.java`
- 新建： `passenger/src/test/java/com/sx/passenger/internal/auth/PassengerInternalAuthControllerTest.java`
- 修改： `passenger/src/main/resources/application.yml`
- 修改： `passenger/src/test/resources/application-test.yml`

**接口：**
- 使用：`AuthoritativeAuthState PassengerAuthEpochService.loadState(long)`；`long PassengerAuthEpochService.logout(long,long)`。
- 产出：`GET /api/v1/internal/auth-state/{customerId}`；`POST /api/v1/internal/auth-state/logout`；请求头 `X-Internal-Service-Token`。

- [ ] **步骤 1： 写内部身份过滤器与生产配置失败测试**

```java
@WebMvcTest(PassengerInternalAuthController.class)
class PassengerInternalAuthFilterTest {
    @Test void rejectsMissingOrWrongInternalToken() throws Exception {
        mvc.perform(get("/api/v1/internal/auth-state/7")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/internal/auth-state/7")
                .header("X-Internal-Service-Token", "wrong")).andExpect(status().isForbidden());
    }

    @Test void acceptsExactInternalToken() throws Exception {
        mvc.perform(get("/api/v1/internal/auth-state/7")
                .header("X-Internal-Service-Token", "test-passenger-internal-secret-32bytes"))
                .andExpect(status().isOk());
    }
}
```

启动校验测试固定覆盖：prod 空值、少于 32 bytes、`dev-passenger-` 前缀、`change-me` 均抛 `IllegalStateException`；`local/dev/test` 放宽以便本地启动。

- [ ] **步骤 2： 运行测试确认 RED**

运行： `mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalSecurityStartupValidatorTest test`

预期： FAIL，内部安全类不存在。

- [ ] **步骤 3： 实现常量时间 Token 校验与路径范围**

```java
@ConfigurationProperties("passenger.internal-auth")
public class PassengerInternalAuthProperties { private String token; /* getter/setter */ }

private boolean matches(String supplied) {
    byte[] expected = properties.getToken().getBytes(StandardCharsets.UTF_8);
    byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, actual);
}
```

Filter 只覆盖 `/api/v1/app/**` 与 `/api/v1/internal/**`，缺 Header 返回 401、错误值返回 403；安全日志只写 URI、requestId、结果码，不写 Header 值。生产 validator 沿用 `PassengerSecurityStartupValidator` 的 profile 判定风格。

- [ ] **步骤 4： 写认证状态和 过期登出 的 Controller 失败测试**

```java
@Test void returnsCompleteAuthoritativeState() throws Exception {
    when(service.loadState(7L)).thenReturn(new AuthoritativeAuthState(
            7L, 0, "CANCELLING", 9L, "op-1", AuthSessionScope.LIFECYCLE_RESTRICTED, true));
    mvc.perform(get("/api/v1/internal/auth-state/7").header(INTERNAL, TOKEN))
       .andExpect(jsonPath("$.data.authEpoch").value(9))
       .andExpect(jsonPath("$.data.allowedScope").value("LIFECYCLE_RESTRICTED"))
       .andExpect(jsonPath("$.data.currentLifecycleOperationNo").value("op-1"));
}

@Test void staleLogoutReturnsConflict() throws Exception {
    when(service.logout(7L, 8L)).thenThrow(new AuthEpochConflictException());
    mvc.perform(post("/api/v1/internal/auth-state/logout").header(INTERNAL, TOKEN)
            .contentType(APPLICATION_JSON).content("{\"customerId\":7,\"expectedAuthEpoch\":8}"))
       .andExpect(status().isConflict());
}
```

- [ ] **步骤 5： 实现只做适配的内部 Controller**

Controller 不写 mapper、不判断 lifecycle 规则，只把 service 结果映射成 DTO。`InternalAuthStateResponse` 字段固定为 `customerId/businessStatus/lifecycleStatus/authEpoch/currentLifecycleOperationNo/allowedScope/allowed`；不存在或禁止状态也返回 200 + `allowed=false`，基础设施故障由统一异常处理映射 503。

- [ ] **步骤 6： 配置环境变量且禁止密钥入库**

```yaml
passenger:
  internal-auth:
    token: ${PASSENGER_INTERNAL_TOKEN:dev-passenger-internal-change-me}
```

测试配置覆盖为至少 32 bytes 的 test secret。不得在任何 Java 测试输出或日志中打印该值。

- [ ] **步骤 7： 运行 任务 3 测试确认 GREEN**

运行： `mvn -pl passenger -Dtest=PassengerInternalAuthFilterTest,PassengerInternalSecurityStartupValidatorTest,PassengerInternalAuthControllerTest,PassengerAuthEpochServiceIntegrationTest test`

预期： PASS；未带内部身份不能调用既有 `/api/v1/app/**` 或新增 `/api/v1/internal/**`。

- [ ] **步骤 8： 提交 任务 3**

```bash
git add passenger/src/main passenger/src/test
git commit -m "功能：保护乘客内部认证状态接口"
```

