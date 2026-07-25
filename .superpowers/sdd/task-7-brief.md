### Task 7:实现未公开的同 customer.id 换号事务

**文件：**
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/phone/ChangeCustomerPhoneCommand.java`
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/phone/ChangeCustomerPhoneResult.java`
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/phone/CustomerPhoneChangeService.java`
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/phone/PhoneBindingValueFactory.java`
- 新建： `passenger/src/test/java/com/sx/passenger/lifecycle/application/phone/CustomerPhoneChangeServiceIntegrationTest.java`
- 修改： `passenger/src/main/java/com/sx/passenger/dao/CustomerEntityMapper.java`
- 修改： `passenger/src/main/resources/mapper/CustomerEntityMapper.xml`
- 修改： `passenger/src/main/java/com/sx/passenger/lifecycle/persistence/mapper/CustomerPhoneBindingHistoryMapper.java`
- 修改： `passenger/src/main/java/com/sx/passenger/lifecycle/persistence/mapper/LifecycleOperationMapper.java`
- 修改： `passenger/src/main/resources/mapper/lifecycle/LifecycleOperationMapper.xml`

**接口：**
- 使用： `AtomicOtpService.consume(PHONE_CHANGE_NEW_PHONE,...)`；`LifecycleRequestHasher`；P1 SnapshotFactory/Store。
- 产出： `ChangeCustomerPhoneResult change(ChangeCustomerPhoneCommand command)`，返回相同 customerId、新 authEpoch、operationNo 与 `requireLogin=true`，没有 Controller。

- [ ] **步骤 1： 写身份保持与绑定历史失败测试**

```java
@Test void changesPhoneWithoutChangingCustomerIdentity() {
    ChangeCustomerPhoneResult out = service.change(command(customerId, 3L, "13900139000"));
    Customer current = customers.selectById(customerId);
    assertThat(current.getId()).isEqualTo(customerId);
    assertThat(current.getPhone()).isEqualTo("13900139000");
    assertThat(current.getLifecycleVersion()).isEqualTo(4L);
    assertThat(current.getAuthEpoch()).isEqualTo(6L);
    assertThat(out.requireLogin()).isTrue();
}

@Test void replacesOldBindingAndCreatesNextActiveBinding() {
    service.change(command(customerId, 3L, "13900139000"));
    assertThat(bindings(customerId)).extracting("bindingVersion", "status")
            .containsExactly(tuple(1L, "REPLACED"), tuple(2L, "ACTIVE"));
    assertThat(activeBinding(customerId).getChangeOperationNo()).isEqualTo(operation(customerId).getOperationNo());
}
```

- [ ] **步骤 2： 写幂等、手机号占用和事务回滚失败测试**

测试固定包含：相同 idempotencyKey+hash 重放不消费第二次 OTP；同键不同 hash 为 409；新旧手机号相同为 400 且不消费 OTP；`phone_active` 唯一冲突映射 409；history/Event/Outbox 任一写失败使 customer phone/version/epoch、Operation、步骤 全回滚，OTP 不恢复；与 任务 6 注销 fence 共享 预期 version 并发时只有一个成功。

- [ ] **步骤 3： 运行测试确认 RED**

运行： `mvn -pl passenger -Dtest=CustomerPhoneChangeServiceIntegrationTest test`

预期： FAIL，换号 应用服务 与 CAS mapper 方法不存在。

- [ ] **步骤 4： 添加保持 customer.id 的换号 CAS**

```xml
<update id="changePhoneCas">
  UPDATE customer
  SET phone = #{newPhone}, lifecycle_version = lifecycle_version + 1,
      auth_epoch = auth_epoch + 1, updated_at = #{updatedAt}
  WHERE id = #{customerId} AND is_deleted = 0 AND lifecycle_status = 'ACTIVE'
    AND lifecycle_version = #{expectedLifecycleVersion}
    AND current_lifecycle_operation_no IS NULL
</update>
```

依赖已执行 SQL 中的 `phone_active` 唯一索引处理抢占；捕获 `DuplicateKeyException` 并映射 409，禁止创建新 customer 或迁移订单、钱包、券、积分。

- [ ] **步骤 5： 实现绑定历史的版本化更新**

```java
long replaceActive(long customerId, String operationNo, LocalDateTime now);
Long selectMaxBindingVersion(long customerId);
```

在同一 MySQL 事务中先把当前 ACTIVE 更新为 `REPLACED/valid_to/updated_at/change_operation_no`，再插入 `maxVersion+1` 的 ACTIVE。`PhoneBindingValueFactory` 沿用当前表的 `phoneCiphertext/phoneIdentityHash/hashKeyVersion` 存储契约；P2 不重构明文/密文策略，也不改变已经确认的表结构。手机号 hash 必须稳定用于判等，日志仍只使用掩码。

- [ ] **步骤 6： 创建并完成 PHONE_CHANGE 运行快照**

```java
LifecycleRuntimeSnapshot snapshot = snapshotFactory.create(new CreateLifecycleSnapshotCommand(
        command.customerId(), PHONE_CHANGE, command.idempotencyKey(), requestHash,
        command.expectedLifecycleVersion(), CUSTOMER, command.actorId(), command.traceId(),
        command.sanitizedRequestContextJson(), command.requestedAt()));
snapshots.persistNew(snapshot);
```

同一事务内按状态机执行 `REQUESTED -> EXECUTING -> COMPLETED`，将所有 PHONE_CHANGE 步骤 标记 COMPLETED，写两条状态 Event，并写 `account.lifecycle.phone-changed.v1` 完成 outbox；Operation 的 `applied_lifecycle_version` 使用更新后的 customer version，`completed_at` 非空。

- [ ] **步骤 7： 实现固定顺序的换号服务**

顺序固定为：计算 请求哈希 → 查幂等 Operation（成功重放必须在手机号已经变更后仍直接返回）→ 读取 customer 并校验新旧手机号 → 原子消费绑定 expectedLifecycleVersion 的 OTP → 开启 MySQL 事务 → 生成 Snapshot → customer CAS → 旧绑定 REPLACED/新绑定 ACTIVE → persistNew → 完成 Operation/Step/Event/Outbox → 返回新 epoch。业务校验不通过时不消费 OTP；OTP 消费后的任意数据库失败要求重新获取验证码。

- [ ] **步骤 8： 运行 任务 7 测试确认 GREEN**

运行： `mvn -pl passenger -Dtest=CustomerPhoneChangeServiceIntegrationTest,AccountCancellationFenceServiceIntegrationTest,LifecycleSnapshotStoreIntegrationTest test`

预期： PASS；断言换号前后 customer.id 完全相同，且并发换号/注销仅一方成功。

- [ ] **步骤 9： 提交 任务 7**

```bash
git add passenger/src/main passenger/src/test
git commit -m "功能：建立乘客同身份换号事务"
```

