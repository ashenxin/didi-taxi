### Task 2:在 passenger 建立数据库权威认证代次与会话材料

**文件：**
- 新建： `passenger/src/main/java/com/sx/passenger/auth/session/AuthSessionScope.java`
- 新建： `passenger/src/main/java/com/sx/passenger/auth/session/AuthoritativeAuthState.java`
- 新建： `passenger/src/main/java/com/sx/passenger/auth/session/AuthStateRejectedException.java`
- 新建： `passenger/src/main/java/com/sx/passenger/auth/session/AuthEpochConflictException.java`
- 新建： `passenger/src/main/java/com/sx/passenger/auth/session/PassengerAuthEpochService.java`
- 新建： `passenger/src/test/java/com/sx/passenger/auth/session/PassengerAuthEpochServiceIntegrationTest.java`
- 修改： `passenger/src/main/java/com/sx/passenger/dao/CustomerEntityMapper.java`
- 修改： `passenger/src/main/resources/mapper/CustomerEntityMapper.xml`
- 修改： `passenger/src/main/java/com/sx/passenger/lifecycle/persistence/mapper/LifecycleOperationMapper.java`
- 修改： `passenger/src/main/resources/mapper/lifecycle/LifecycleOperationMapper.xml`
- 修改： `passenger/src/main/java/com/sx/passenger/app/dto/AppAuthCustomerBrief.java`
- 修改： `passenger/src/main/java/com/sx/passenger/app/AppCustomerAuthService.java`
- 修改： `passenger/src/test/java/com/sx/passenger/app/AppCustomerAuthServiceTest.java`
- 修改： `passenger/src/main/java/com/sx/passenger/app/AppCustomerSettingsService.java`
- 修改： `passenger/src/test/java/com/sx/passenger/app/AppCustomerSettingsServiceTest.java`

**接口：**
- 使用：`CustomerEntityMapper.bumpAuthEpochForAuthentication(long)`；`LifecycleOperationMapper.updateRestrictedAuthEpoch(long, String, long, LocalDateTime)`。
- 产出：`AppAuthCustomerBrief PassengerAuthEpochService.completeAuthentication(long customerId)`；`long PassengerAuthEpochService.logout(long customerId, long expectedAuthEpoch)`；`AuthoritativeAuthState PassengerAuthEpochService.loadState(long customerId)`。

- [ ] **步骤 1：写 ACTIVE/CANCELLING/CANCELLED 和并发 epoch 失败测试**

```java
@SpringBootTest
@Transactional
class PassengerAuthEpochServiceIntegrationTest {
    @Test void activeAuthenticationBumpsEpochAndReturnsNormal() {
        AppAuthCustomerBrief out = service.completeAuthentication(activeCustomerId);
        assertThat(out.getAuthEpoch()).isEqualTo(2L);
        assertThat(out.getScope()).isEqualTo("NORMAL");
        assertThat(out.getOperationNo()).isNull();
    }

    @Test void cancellingAuthenticationBumpsBothCustomerAndOperationEpoch() {
        AppAuthCustomerBrief out = service.completeAuthentication(cancellingCustomerId);
        assertThat(out.getScope()).isEqualTo("LIFECYCLE_RESTRICTED");
        assertThat(out.getOperationNo()).isEqualTo("op-cancel-1");
        assertThat(operation("op-cancel-1").getRestrictedAuthEpoch()).isEqualTo(out.getAuthEpoch());
    }

    @Test void cancelledCustomerCannotAuthenticate() {
        assertThatThrownBy(() -> service.completeAuthentication(cancelledCustomerId))
                .isInstanceOf(AuthStateRejectedException.class);
    }

    @Test void staleLogoutCannotInvalidateNewerLogin() {
        long loginEpoch = service.completeAuthentication(activeCustomerId).getAuthEpoch();
        long newerEpoch = service.completeAuthentication(activeCustomerId).getAuthEpoch();
        assertThatThrownBy(() -> service.logout(activeCustomerId, loginEpoch))
                .isInstanceOf(AuthEpochConflictException.class);
        assertThat(service.loadState(activeCustomerId).authEpoch()).isEqualTo(newerEpoch);
    }
}
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest test`

预期：FAIL，认证代次服务和 mapper 方法不存在。

- [ ] **步骤 3：添加仅由 CAS 修改 epoch 的 mapper SQL**

```xml
<update id="bumpAuthEpochForAuthentication">
  UPDATE customer
  SET auth_epoch = auth_epoch + 1,
      updated_at = CURRENT_TIMESTAMP
  WHERE id = #{customerId}
    AND is_deleted = 0
    AND status = 0
    AND lifecycle_status IN ('ACTIVE', 'CANCELLING')
</update>

<update id="bumpAuthEpochForLogout">
  UPDATE customer
  SET auth_epoch = auth_epoch + 1,
      updated_at = CURRENT_TIMESTAMP
  WHERE id = #{customerId}
    AND is_deleted = 0
    AND auth_epoch = #{expectedAuthEpoch}
    AND lifecycle_status IN ('ACTIVE', 'CANCELLING')
</update>
```

`LifecycleOperationMapper.updateRestrictedAuthEpoch` 必须带 `operation_no + customer_id + 非终态` 条件，更新条数不是 1 时让外层事务回滚。

- [ ] **步骤 4：实现 PassengerAuthEpochService 的事务边界**

```java
@Transactional
public AppAuthCustomerBrief completeAuthentication(long customerId) {
    if (customers.bumpAuthEpochForAuthentication(customerId) != 1) throw new AuthStateRejectedException();
    Customer current = customers.selectById(customerId);
    AuthSessionScope scope = scopeOf(current);
    if (scope == AuthSessionScope.LIFECYCLE_RESTRICTED) {
        if (operations.updateRestrictedAuthEpoch(customerId, current.getCurrentLifecycleOperationNo(),
                current.getAuthEpoch(), LocalDateTime.now(ZoneOffset.UTC)) != 1) {
            throw new AuthStateRejectedException();
        }
    }
    return AppAuthCustomerBrief.from(current, scope.name());
}

@Transactional
public long logout(long customerId, long expectedAuthEpoch) {
    if (customers.bumpAuthEpochForLogout(customerId, expectedAuthEpoch) != 1) {
        throw new AuthEpochConflictException();
    }
    return customers.selectById(customerId).getAuthEpoch();
}
```

`loadState` 必须把不存在、`is_deleted=1`、`CANCELLED` 映射为拒绝状态；不得自动修改数据。

- [ ] **步骤 5：登录成功点改为数据库 bump 后再返回**

密码校验通过、LOGIN OTP 成功且注册完成后统一调用 `completeAuthentication(c.getId())`。新注册必须显式初始化 `lifecycleStatus=ACTIVE`、`lifecycleVersion=0`、`authEpoch=0`；成功登录返回的新 epoch 至少为 1。

- [ ] **步骤 6：保持旧 settings 编排但把失效事实原子迁到数据库**

旧 `confirmPhoneChange` 仍只做即时换号，旧 `confirmAccountCancel` 仍只做即时逻辑删除，不创建 lifecycle Operation。将两者的更新改成 mapper SQL：换号在同一条 UPDATE 中 `phone=#{newPhone}, auth_epoch=auth_epoch+1`；注销在同一条 UPDATE 中 `is_deleted=1, auth_epoch=auth_epoch+1, lifecycle_status='CANCELLED', cancelled_at=#{now}`。两条 SQL 都带当前 `lifecycle_version` 与 `lifecycle_status='ACTIVE'` CAS，冲突返回 409；这样 P2 删除 Redis tv 后旧公开功能仍能立即使 Token 失效，但没有提前切换 Saga。

- [ ] **步骤 7：运行任务 2 测试确认 GREEN**

运行：`mvn -pl passenger -Dtest=PassengerAuthEpochServiceIntegrationTest,AppCustomerAuthServiceTest,AppCustomerSettingsServiceTest test`

预期：PASS；并发登录产生不同 epoch，旧 epoch 的 logout CAS 为 0 且不改变新 epoch。

- [ ] **步骤 8：提交任务 2**

```bash
git add passenger/src/main passenger/src/test
git commit -m "功能：以数据库认证代次签发会话材料"
```

