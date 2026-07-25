### Task 6:实现未公开的账号注销建栅栏应用服务

**文件：**
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/cancel/FenceAccountCancellationCommand.java`
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/cancel/AccountCancellationFenceResult.java`
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/cancel/AccountCancellationFenceService.java`
- 新建： `passenger/src/main/java/com/sx/passenger/lifecycle/application/LifecycleRequestHasher.java`
- 新建： `passenger/src/test/java/com/sx/passenger/lifecycle/application/cancel/AccountCancellationFenceServiceIntegrationTest.java`
- 修改： `passenger/src/main/java/com/sx/passenger/dao/CustomerEntityMapper.java`
- 修改： `passenger/src/main/resources/mapper/CustomerEntityMapper.xml`
- 修改： `passenger/src/main/java/com/sx/passenger/lifecycle/persistence/mapper/LifecycleOperationMapper.java`
- 修改： `passenger/src/main/resources/mapper/lifecycle/LifecycleOperationMapper.xml`

**接口：**
- 使用： `AtomicOtpService.consume(ACCOUNT_CANCEL,...)`；`LifecycleSnapshotStore.findByIdempotency/persistNew`；`LifecycleRuntimeSnapshotFactory.create`。
- 产出： `AccountCancellationFenceResult fence(FenceAccountCancellationCommand command)`，仅供后续 P3/P7 内部编排调用，没有 Controller。

- [ ] **步骤 1： 写幂等先于 OTP 的失败测试**

```java
@Test void sameIdempotencyAndHashReturnsExistingWithoutConsumingOtp() {
    AccountCancellationFenceResult out = service.fence(command("idem-1", "111111", "same-payload"));
    AccountCancellationFenceResult replay = service.fence(command("idem-1", "expired", "same-payload"));
    assertThat(replay.operationNo()).isEqualTo(out.operationNo());
    verify(otp, times(1)).consume(eq(ACCOUNT_CANCEL), any(), anyString());
}

@Test void sameKeyDifferentHashConflictsBeforeOtp() {
    service.fence(command("idem-1", "111111", "payload-a"));
    assertThatThrownBy(() -> service.fence(command("idem-1", "222222", "payload-b")))
            .isInstanceOf(LifecycleOperationConflictException.class);
    verify(otp, times(1)).consume(eq(ACCOUNT_CANCEL), any(), anyString());
}
```

- [ ] **步骤 2： 写原子事务和并发 CAS 失败测试**

覆盖：ACTIVE/version 匹配成功后 customer 为 `CANCELLING`、version+1、epoch+1、当前 Operation 已设置；Operation/Step/Event/Outbox 均存在；Operation 为 FENCED 且 `restricted_auth_epoch` 等于 customer epoch。对 SnapshotStore 插入 Event/Outbox 的故障注入后断言五类数据与 customer 全回滚，但 OTP 仍为已消费。换号与注销用相同 预期 version 并发时只能一方 CAS 成功。

- [ ] **步骤 3： 运行测试确认 RED**

运行： `mvn -pl passenger -Dtest=AccountCancellationFenceServiceIntegrationTest test`

预期： FAIL，注销建栅栏服务和 CAS SQL 不存在。

- [ ] **步骤 4： 实现稳定 请求哈希 与命令校验**

```java
public record FenceAccountCancellationCommand(long customerId, long expectedLifecycleVersion,
        String otpCode, String idempotencyKey, String actorId, String traceId,
        String sanitizedRequestContextJson, Instant requestedAt) {}

String canonical = "ACCOUNT_CANCEL\n" + customerId + "\n" + expectedLifecycleVersion
        + "\n" + canonicalJson(sanitizedRequestContextJson);
String requestHash = HexFormat.of().formatHex(sha256.digest(canonical.getBytes(UTF_8)));
```

hash 不包含 OTP、traceId 或当前时间；相同业务请求重放必须稳定。

- [ ] **步骤 5： 添加 customer lifecycle fence CAS**

```xml
<update id="fenceAccountCancellation">
  UPDATE customer
  SET lifecycle_status = 'CANCELLING', lifecycle_version = lifecycle_version + 1,
      auth_epoch = auth_epoch + 1, current_lifecycle_operation_no = #{operationNo},
      updated_at = #{updatedAt}
  WHERE id = #{customerId} AND is_deleted = 0 AND lifecycle_status = 'ACTIVE'
    AND lifecycle_version = #{expectedLifecycleVersion}
    AND current_lifecycle_operation_no IS NULL
</update>
```

Operation 从 REQUESTED 到 FENCED 的 mapper 更新必须在同一事务内同时设置 `restricted_auth_epoch/fenced_at/applied_lifecycle_version/row_version+1`，并插入状态变化 Event；不要调用另开事务的异步逻辑。

- [ ] **步骤 6： 实现固定顺序的 fence 服务**

```java
public AccountCancellationFenceResult fence(FenceAccountCancellationCommand command) {
    String hash = hasher.hash(command);
    Optional<LifecycleOperationEntity> prior = snapshots.findByIdempotency(
            command.customerId(), ACCOUNT_CANCEL, command.idempotencyKey());
    if (prior.isPresent()) return requireSameHashAndMap(prior.get(), hash);
    requireConsumed(otp.consume(ACCOUNT_CANCEL,
            OtpSubject.accountCancel(command.customerId(), command.expectedLifecycleVersion()), command.otpCode()));
    return transactionTemplate.execute(status -> createFence(command, hash));
}
```

OTP 必须在 MySQL 事务外消费；`createFence` 的顺序为生成 Snapshot（先得到 operationNo）→ customer CAS → `persistNew` → Operation FENCED CAS + Event → 返回结果。任何写入数量不为 1 都抛异常回滚。

- [ ] **步骤 7： 运行 任务 6 测试确认 GREEN**

运行： `mvn -pl passenger -Dtest=AccountCancellationFenceServiceIntegrationTest,LifecycleSnapshotStoreIntegrationTest,LifecycleOperationTransitionServiceTest test`

预期： PASS；故障注入证明 MySQL 整体回滚且 OTP 未恢复。

- [ ] **步骤 8： 提交 任务 6**

```bash
git add passenger/src/main passenger/src/test
git commit -m "功能：建立乘客账号注销栅栏事务"
```

