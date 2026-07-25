### Task 8:完成切换配置、可观测性、删除 Redis tv 并做全量验收

**文件：**
- 新建： `passenger/src/main/java/com/sx/passenger/auth/metrics/PassengerAuthMetrics.java`
- 新建： `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerAuthMetrics.java`
- 新建： `passenger-api/src/test/java/com/sx/passengerapi/auth/PassengerAuthMetricsTest.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/config/AppJwtProperties.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/config/PassengerSecurityStartupValidator.java`
- 修改： `passenger-api/src/test/java/com/sx/passengerapi/config/PassengerSecurityStartupValidatorTest.java`
- 修改： `passenger-api/src/main/resources/application.yml`
- 修改： `passenger-api/src/main/resources/application-local.yml`
- 修改： `passenger-api/src/main/resources/application-dev.yml`
- 修改： `passenger-api/src/test/resources/application-test.yml`
- 修改： `passenger/src/main/resources/application.yml`
- 修改： `passenger/src/test/resources/application-test.yml`
- 新建： `docs/superpowers/contracts/passenger-auth-state-v1.json`
- 删除： `passenger-api/src/main/java/com/sx/passengerapi/auth/PassengerTokenVersionStore.java`
- 修改： `docs/superpowers/specs/2026-07-21-乘客认证代次原子OTP与受限会话-design.md`

**接口：**
- 使用： 任务 1-7 的稳定 API。
- 产出： 完整 P2 cutover 配置、指标名、上线/回滚 runbook 和无 `tv` 的可验证代码基线。

- [ ] **步骤 1： 写生产启动和指标失败测试**

```java
@Test void productionRejectsMissingOrDevelopmentInternalToken() {
    assertThatThrownBy(() -> PassengerSecurityStartupValidator.validateStrict(jwt, coupon,
            internal("dev-passenger-internal-change-me")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PASSENGER_INTERNAL_TOKEN");
}

@Test void recordsDatabaseDecisionAndRejectReasonWithoutIdentifiers() {
    metrics.authStateQuery(Duration.ofMillis(12), "success");
    metrics.jwtRejected("epoch_mismatch");
    assertThat(registry.get("passenger.auth.state.query").timer().count()).isEqualTo(1);
    assertThat(registry.get("passenger.auth.jwt.rejected").counter().count()).isEqualTo(1);
}
```

- [ ] **步骤 2： 运行测试确认 RED**

运行： `mvn -pl passenger-api -Dtest=PassengerSecurityStartupValidatorTest,PassengerAuthMetricsTest test`

预期： FAIL，内部 Token 尚未纳入生产 validator，指标组件不存在。

- [ ] **步骤 3： 添加固定低基数指标**

指标固定为：`passenger.auth.state.query`（result）、`passenger.auth.jwt.rejected`（reason）、`passenger.auth.otp.consume`（purpose/result）、`passenger.auth.epoch.bump`（cause/result）、`passenger.auth.restricted.issued`、`passenger.auth.ws.closed`（reason）、`passenger.lifecycle.cas.conflict`（operationType）。Tag 白名单必须由 enum/switch 产生；禁止 customerId、operationNo、手机号、OTP Key、异常 message 进入 tag。

- [ ] **步骤 4： 完成两端配置和生产校验**

```yaml
app:
  jwt:
    audience: app-bff
    expiration-seconds: ${JWT_EXPIRATION_SECONDS_APP:86400}
    restricted-expiration-seconds: ${JWT_RESTRICTED_EXPIRATION_SECONDS_APP:1800}
passenger:
  internal-auth:
    token: ${PASSENGER_INTERNAL_TOKEN:dev-passenger-internal-change-me}
```

passenger-api 与 passenger 使用同一环境变量名。非 `local/dev/test` 同时校验长度至少 32 bytes、不得含 `change-me`、不得以 `dev-passenger-` 开头；JWT audience 仍必须为 `app-bff`。

- [ ] **步骤 5： 删除 Redis tv 实现并做静态切换扫描**

运行： `rg -n 'PassengerTokenVersionStore|passenger:tv:|claim\("tv"\)|get\("tv"\)|tokenVersion|\.tokenVersion\(' passenger-api/src passenger/src`

预期： 无输出。若测试夹具需要构造“旧 tv Token”，只允许测试文件中出现字面量，并将扫描范围改为 `*/src/main` 后再次确认无输出。

运行： `rg -n 'app:otp:(?!v2)|KEY_OTP_PREFIX|verifyCode\(|opsForValue\(\)\.get\(otp|delete\(otp' passenger/src/main --pcre2`

预期： 无旧 OTP 成功路径；频控 Redis get/delete 不在匹配范围内。

- [ ] **步骤 6： 增加跨模块认证契约集成测试**

先创建 `docs/superpowers/contracts/passenger-auth-state-v1.json`，固定 ACTIVE、CANCELLING 和拒绝三种响应字段/类型；passenger Controller 测试与 passenger-api client 测试都读取同一 fixture。新增场景：登录得到 epoch N → passenger-api 验证 NORMAL 成功 → 第二次登录得到 N+1 → 旧 HTTP/WS Token 拒绝 → CANCELLING 重认证得到 restricted → restricted 普通接口 403、WS token 403 → passenger 模拟超时/5xx 时 HTTP 与 WS 都 503。passenger-api 的请求鉴权不得调用 Redis；“Redis 缺失/不可用不影响裁决”由零 Redis 认证依赖和该契约测试共同证明。

- [ ] **步骤 7： 更新设计文档的实现状态与上线检查单**

在设计文档末尾新增“P2 实现结果”：列出实际类名、内部路径、环境变量、指标名、旧 settings 保留方式；上线检查单固定为先 passenger、后 passenger-api、统一停止接受 tv、强制重新登录、观察 401/403/503 与 DB P99。回滚明确禁止降低 auth_epoch、恢复旧 Token 或重新把 Redis tv 设为权威。

- [ ] **步骤 8： 运行模块全量验证**

运行： `mvn -pl passenger verify`

预期： BUILD SUCCESS；全部单元/集成测试和 JaCoCo 检查通过。

运行： `mvn -pl passenger-api verify`

预期： BUILD SUCCESS；全部单元测试、Spring context 与 JaCoCo 检查通过。

运行： `mvn -pl passenger,passenger-api -am verify`

预期： BUILD SUCCESS；上游依赖联编无契约漂移。

- [ ] **步骤 9： 检查未暴露 Controller 和密钥泄漏**

运行： `rg -n '@(Get|Post|Put|Delete|Patch)Mapping.*(lifecycle|phone-change|account-cancel)' passenger/src/main/java/com/sx/passenger/lifecycle`

预期： 无输出。

运行： `git grep -nE 'PASSENGER_INTERNAL_TOKEN=.+' -- ':!docs/superpowers/plans/*' ':!*.example'`

预期： 无硬编码密钥；仅 `${PASSENGER_INTERNAL_TOKEN:...}` 开发默认配置存在。

- [ ] **步骤 10： 提交 任务 8**

```bash
git add passenger passenger-api docs/superpowers/specs docs/superpowers/contracts
git commit -m "重构：完成乘客认证代次P2切换"
```

## 最终验收矩阵

| 场景 | 预期结果 | 证据所在任务 |
|---|---|---|
| LOGIN/换号/注销 OTP 并发 | 仅一个 CONSUMED，Purpose 不串用 | 任务 1 |
| ACTIVE/CANCELLING/CANCELLED 登录 | NORMAL / restricted / 拒绝 | 任务 2、5 |
| 旧请求并发登出新会话 | logout epoch CAS 冲突，不影响新会话 | 任务 2、5 |
| Redis 丢失/不可用 | 仍查询 passenger DB，不错误放行 | 任务 4、8 |
| passenger/DB 不可用 | HTTP 与 WS 503，失败关闭 | 任务 4、8 |
| 旧 tv、缺 ae/scope、受限 WS | 401/403 | 任务 4 |
| 注销栅栏部分写失败 | customer/Operation/Step/Event/Outbox 全回滚 | 任务 6 |
| 换号/注销版本竞争 | 仅一方 lifecycleVersion CAS 成功 | 任务 6、7 |
| 换号身份连续性 | customer.id 不变，绑定历史版本正确 | 任务 7 |
| epoch 变化后的本节点 WS | 立即关闭，旧 WS Token 重连拒绝 | 任务 4、5 |
| 旧 settings 兼容 | 不切 Saga，但数据库认证状态即时失效 | 任务 1、2、5 |
| P2 暴露面 | 无新公开 lifecycle Controller | 任务 8 |
