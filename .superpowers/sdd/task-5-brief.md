### Task 5:重接 BFF 登录、WS Token 与先失效后清单的登出流程

**文件：**
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/client/dto/AppAuthCustomerBrief.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/model/auth/CustomerLoginResponse.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/model/auth/PassengerLogoutResult.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/controller/PassengerAuthController.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/service/PassengerAuthService.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/service/PassengerSettingsService.java`
- 修改： `passenger-api/src/main/java/com/sx/passengerapi/ws/PassengerWsSessionRegistry.java`
- 修改： `passenger-api/src/test/java/com/sx/passengerapi/service/PassengerAuthServiceTest.java`
- 新建： `passenger-api/src/test/java/com/sx/passengerapi/ws/PassengerWsSessionRegistryTest.java`

**接口：**
- 使用： 任务 2 登录摘要 `authEpoch/scope/operationNo`；任务 3 登出端点；任务 4 JWT API 和可信 Headers。
- 产出： `PassengerLogoutResult logout(long customerId,long tokenAuthEpoch)`；`CustomerLoginResponse issueWsToken(PassengerAuthContext context)`；`void PassengerWsSessionRegistry.closeCustomerSessions(long,String)`。

- [ ] **步骤 1： 写登录签发与受限 TTL 失败测试**

```java
@Test void signsExactlyTheEpochAndScopeReturnedByCore() {
    when(core.loginPassword(any())).thenReturn(ok(brief(7L, 11L, "NORMAL", null)));
    service.loginPassword("13800138000", "secret");
    verify(jwt).createPassengerToken(7L, "13800138000", 11L, NORMAL, 1, null);
    verify(sessions).closeCustomerSessions(7L, "auth_epoch_changed");
}

@Test void signsRestrictedReauthenticationForThirtyMinutes() {
    when(core.loginSms(any())).thenReturn(ok(brief(7L, 12L, "LIFECYCLE_RESTRICTED", "op-1")));
    CustomerLoginResponse out = service.loginSms("13800138000", "111111");
    assertThat(out.getScope()).isEqualTo("LIFECYCLE_RESTRICTED");
    assertThat(out.getExpiresIn()).isEqualTo(1800L);
}
```

- [ ] **步骤 2： 写 logout 并发与副作用顺序失败测试**

```java
@Test void commitsEpochThenClosesLocalWsThenProcessesOrders() {
    InOrder order = inOrder(authStateClient, sessions, passengerOrderService);
    service.logout(7L, 9L);
    order.verify(authStateClient).logout(new InternalLogoutRequest(7L, 9L));
    order.verify(sessions).closeCustomerSessions(7L, "logout");
    order.verify(passengerOrderService).cancelInFlightOrdersOnPassengerLogout(7L);
}

@Test void orderFailureDoesNotAttemptToRestoreEpoch() {
    when(passengerOrderService.cancelInFlightOrdersOnPassengerLogout(7L))
            .thenThrow(new BizErrorException(502, "order unavailable"));
    PassengerLogoutResult out = service.logout(7L, 9L);
    assertThat(out.isLoggedOut()).isTrue();
    assertThat(out.isOrderCleanupPending()).isTrue();
    verify(authStateClient, times(1)).logout(any());
    verifyNoMoreInteractions(authStateClient);
}
```

Controller 必须从可信 `X-Auth-Epoch` 读取 expected epoch；只传 customerId 的旧签名必须删除，以阻止旧并发请求注销新会话。

- [ ] **步骤 3： 运行测试确认 RED**

运行： `mvn -pl passenger-api -Dtest=PassengerAuthServiceTest,PassengerWsSessionRegistryTest test`

预期： FAIL，服务仍依赖 Redis token version 且登出顺序相反。

- [ ] **步骤 4： 实现登录与 WS Token 签发**

`toLoginResponse` 直接使用 core 摘要，不再 `nextVersion`；core 登录成功已使旧 epoch 失效，因此签发前调用 `closeCustomerSessions(customerId,"auth_epoch_changed")` 关闭本节点旧 WS。`issueWsToken` 接收 Filter 已验证的 `PassengerAuthContext`，只允许 NORMAL，用相同 `authEpoch` 签 `audit=2`；不得再次从 Redis 读版本。`CustomerLoginResponse` 新增 `scope` 和可空 `operationNo`，restricted 的 `expiresIn` 返回 1800。

- [ ] **步骤 5： 实现本节点 WS 精确关闭**

```java
public void closeCustomerSessions(long customerId, String reason) {
    PassengerSession current = byCustomerId.remove(customerId);
    if (current == null) return;
    customerIdBySessionId.remove(current.getSession().getId());
    safeClose(current.getSession(), new CloseStatus(4001, sanitizeReason(reason)));
}
```

`sanitizeReason` 只接受固定枚举值 `logout/phone_changed/account_cancelling/account_cancelled`，其他值转为 `auth_epoch_changed`，绝不拼接手机号或 Token。

- [ ] **步骤 6： 实现登出不可逆顺序与响应**

```java
public PassengerLogoutResult logout(long customerId, long tokenAuthEpoch) {
    authStateClient.logout(new InternalLogoutRequest(customerId, tokenAuthEpoch));
    sessions.closeCustomerSessions(customerId, "logout");
    try {
        PassengerLogoutResult result = passengerOrderService.cancelInFlightOrdersOnPassengerLogout(customerId);
        result.setLoggedOut(true);
        result.setOrderCleanupPending(false);
        return result;
    } catch (RuntimeException ex) {
        log.error("登出已生效但订单处理失败 customerId={}", customerId, ex);
        return PassengerLogoutResult.loggedOutWithPendingCleanup("已经登出，订单处理需重试或查询");
    }
}
```

不得调用“减 epoch”、恢复 Token 或 Redis 补偿。过期登出 的 409 原样返回，不关闭新会话。

- [ ] **步骤 7： 删除 BFF 认证写路径对 PassengerTokenVersionStore 的依赖并关闭旧 settings 会话**

删除 `PassengerAuthService` 与 `PassengerSettingsService` 的 `nextVersion` 调用。任务 2 已让旧 settings 的核心事务递增/终结数据库认证状态；BFF 在换号或注销成功响应后分别调用 `closeCustomerSessions(customerId,"phone_changed")` 与 `closeCustomerSessions(customerId,"account_cancelled")`。旧 settings 仍不创建 lifecycle Operation，不伪造 Saga 结果。

- [ ] **步骤 8： 运行 任务 5 测试确认 GREEN**

运行： `mvn -pl passenger-api -Dtest=PassengerAuthServiceTest,PassengerWsSessionRegistryTest,PassengerSettingsServiceTest test`

预期： PASS；order 失败用例明确返回已登出，且无 epoch 恢复调用。

- [ ] **步骤 9： 提交 任务 5**

```bash
git add passenger-api/src/main passenger-api/src/test
git commit -m "功能：重接乘客登录登出与本地WS撤销"
```

