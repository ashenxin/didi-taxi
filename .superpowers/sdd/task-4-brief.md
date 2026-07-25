### Task 4:将 passenger-api JWT、HTTP Filter 与 WS 握手切换到 ae/scope 强校验

**文件：**
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerSessionScope.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerAuthContext.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerAuthDecisionService.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/auth/InvalidPassengerSessionException.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/client/PassengerCoreAuthStateClient.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/client/dto/InternalAuthStateResponse.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/client/dto/InternalLogoutRequest.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/client/dto/InternalLogoutResponse.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/config/PassengerInternalClientProperties.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/config/PassengerCoreFeignConfiguration.java`
- 新建： `passenger-api/src/test/java/com/sx/passengerapi/auth/AppJwtServiceTest.java`
- 新建： `passenger-api/src/test/java/com/sx/passengerapi/auth/PassengerJwtAuthFilterTest.java`
- 新建： `passenger-api/src/test/java/com/sx/passengerapi/ws/PassengerWsHandshakeInterceptorTest.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/auth/ParsedPassengerJwt.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/auth/AppJwtService.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/config/AppJwtProperties.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerAuthRequestWrapper.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerJwtAuthFilter.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/ws/PassengerWsHandshakeInterceptor.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/client/PassengerCoreAuthClient.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/client/PassengerCoreSettingsClient.java`

**接口：**
- 使用： passenger 任务 3 的 `GET /api/v1/internal/auth-state/{customerId}` 与 `X-Internal-Service-Token`。
- 产出： `createPassengerToken(long customerId,String phone,long authEpoch,PassengerSessionScope scope,int audit,String operationNo)`；`ParsedPassengerJwt(customerId,phone,authEpoch,scope,audit,operationNo)`；可信 Headers `X-Auth-Epoch/X-Auth-Scope/X-Lifecycle-Operation-No`。

- [ ] **步骤 1： 写 JWT 严格契约失败测试**

```java
@Test void roundTripsNormalAndRestrictedClaims() {
    String normal = jwt.createPassengerToken(7L, "13800138000", 5L, NORMAL, 1, null);
    assertThat(jwt.parseAndVerify(normal)).isEqualTo(
            new ParsedPassengerJwt(7L, "13800138000", 5L, NORMAL, 1, null));
    String restricted = jwt.createPassengerToken(7L, "13800138000", 6L,
            LIFECYCLE_RESTRICTED, 1, "op-1");
    assertThat(jwt.parseAndVerify(restricted).operationNo()).isEqualTo("op-1");
}

@Test void rejectsTvMissingScopeAndRestrictedWs() {
    assertThatThrownBy(() -> jwt.parseAndVerify(signClaims(Map.of("tv", 1, "audit", 1))))
            .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> jwt.createPassengerToken(7L, "", 6L,
            LIFECYCLE_RESTRICTED, 2, "op-1")).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **步骤 2： 运行 JWT 测试确认 RED**

运行： `mvn -pl passenger-api -Dtest=AppJwtServiceTest test`

预期： FAIL，旧签名仍使用 `tv` 且没有 scope。

- [ ] **步骤 3： 实现严格 JWT 创建与解析**

```java
public record ParsedPassengerJwt(long customerId, String phone, long authEpoch,
                                 PassengerSessionScope scope, int audit, String operationNo) {}

public String createPassengerToken(long customerId, String phone, long authEpoch,
        PassengerSessionScope scope, int audit, String operationNo) {
    requireValidCombination(scope, audit, operationNo);
    long ttl = scope == LIFECYCLE_RESTRICTED ? props.getRestrictedExpirationSeconds()
                                             : props.getExpirationSeconds();
    return Jwts.builder().subject(Long.toString(customerId))
            .claim("phone", phone == null ? "" : phone).claim("ae", authEpoch)
            .claim("scope", scope.name()).claim("audit", audit)
            .claim("operationNo", operationNo)
            .audience().add(props.getAudience()).and()
            .issuedAt(now()).expiration(expiry(ttl)).signWith(signingKey()).compact();
}
```

解析时 `ae/scope/audit` 缺失、非预期类型、`ae<1`、受限缺 operationNo、普通携带 operationNo、受限 audit=2 全部抛 `JwtException`；删除 audit 缺失时默认 1 的兼容分支。

- [ ] **步骤 4： 写 HTTP 权威校验矩阵失败测试**

覆盖：ACTIVE+NORMAL 放行；CANCELLING+restricted+相同 operationNo 放行；epoch、scope、operationNo 任一不一致返回 401；受限 Token 访问普通业务默认 403；auth-state Feign 超时/5xx 返回 503；任何放行请求都恰好调用一次 DB 状态接口。

```java
verify(authStateClient, times(1)).get(7L);
assertThat(wrapped.getHeader("X-Auth-Epoch")).isEqualTo("9");
assertThat(wrapped.getHeader("X-Auth-Scope")).isEqualTo("NORMAL");
```

- [ ] **步骤 5： 实现统一裁决函数并在 Filter 使用**

```java
public PassengerAuthContext verify(ParsedPassengerJwt token, InternalAuthStateResponse state, int channelAudit) {
    if (!state.isAllowed() || state.getCustomerId() != token.customerId()
            || state.getAuthEpoch() != token.authEpoch()
            || !state.getAllowedScope().equals(token.scope().name())
            || token.audit() != channelAudit
            || !Objects.equals(state.getCurrentLifecycleOperationNo(), token.operationNo())) {
        throw new InvalidPassengerSessionException();
    }
    return PassengerAuthContext.from(token);
}
```

普通公开登录路径仍跳过 Filter。P2 尚未开放动作矩阵，因此 受限 scope 除生命周期专用路径集合外全部 403；该集合当前为空，登录本身是公开路径。状态客户端超时、连接失败或 5xx 映射 503，绝不降级放行。

- [ ] **步骤 6： 为全部 passenger 核心 Feign 调用添加内部 Header**

```java
@Bean
RequestInterceptor passengerInternalToken(PassengerInternalClientProperties properties) {
    return template -> template.header("X-Internal-Service-Token", properties.getToken());
}
```

只在 `PassengerCoreAuthClient`、`PassengerCoreSettingsClient`、`PassengerCoreAuthStateClient` 的 `configuration=PassengerCoreFeignConfiguration.class` 生效，不能污染 order/calculate 等 Feign 请求。

- [ ] **步骤 7： 写并实现 WS 握手强校验**

WS 仅接受 `NORMAL + audit=2`，并调用同一 auth-state client 校验 ACTIVE、epoch 和空 operationNo。受限 Token 返回 403，签名/epoch 错误返回 401，passenger 不可用返回 503。删除 `PassengerTokenVersionStore` 依赖。

- [ ] **步骤 8： 运行 任务 4 测试确认 GREEN**

运行： `mvn -pl passenger-api -Dtest=AppJwtServiceTest,PassengerJwtAuthFilterTest,PassengerWsHandshakeInterceptorTest test`

预期： PASS；测试断言没有任何 `tv` 兼容路径。

- [ ] **步骤 9： 提交 任务 4**

```bash
git add passenger-api/src/main passenger-api/src/test
git commit -m "功能：以认证代次强校验乘客HTTP与WS令牌"
```

