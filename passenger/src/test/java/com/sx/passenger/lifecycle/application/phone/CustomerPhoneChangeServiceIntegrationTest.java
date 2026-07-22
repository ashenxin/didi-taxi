package com.sx.passenger.lifecycle.application.phone;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.auth.otp.AtomicOtpService;
import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.auth.otp.OtpSubject;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.LifecycleOperationConflictException;
import com.sx.passenger.lifecycle.application.LifecycleSnapshotStore;
import com.sx.passenger.lifecycle.application.cancel.AccountCancellationFenceService;
import com.sx.passenger.lifecycle.application.cancel.FenceAccountCancellationCommand;
import com.sx.passenger.lifecycle.persistence.entity.CustomerPhoneBindingHistoryEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.mapper.CustomerPhoneBindingHistoryMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import com.sx.passenger.model.Customer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class CustomerPhoneChangeServiceIntegrationTest {
    private static final long CUSTOMER_ID = 27_001L;
    private static final String OLD_PHONE = "13800270001";
    private static final String NEW_PHONE = "13900270001";

    @Autowired CustomerPhoneChangeService service;
    @Autowired AccountCancellationFenceService cancellation;
    @Autowired CustomerEntityMapper customers;
    @SpyBean LifecycleSnapshotStore snapshots;
    @SpyBean CustomerPhoneBindingHistoryMapper histories;
    @Autowired LifecycleOperationMapper operations;
    @Autowired LifecycleStepMapper steps;
    @SpyBean LifecycleEventMapper events;
    @SpyBean LifecycleOutboxMapper outboxes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate outerTransactions;
    @MockBean AtomicOtpService otp;

    @BeforeEach
    void setUp() {
        reset(otp, snapshots, histories, events, outboxes);
        cleanRows();
        insertCustomer(CUSTOMER_ID, OLD_PHONE, 3L, 5L);
        insertActiveBinding(CUSTOMER_ID, 1L, OLD_PHONE);
        when(otp.consume(any(), any(), any())).thenReturn(OtpConsumeResult.CONSUMED);
    }

    @AfterEach
    void tearDown() {
        reset(snapshots, histories, events, outboxes);
        cleanRows();
    }

    @Test
    void changesPhoneWithoutChangingCustomerIdentity() {
        ChangeCustomerPhoneResult out = service.change(command("idem-success", NEW_PHONE, "{}"));

        Customer current = customers.selectById(CUSTOMER_ID);
        assertThat(current.getId()).isEqualTo(CUSTOMER_ID);
        assertThat(current.getPhone()).isEqualTo(NEW_PHONE);
        assertThat(current.getLifecycleVersion()).isEqualTo(4L);
        assertThat(current.getAuthEpoch()).isEqualTo(6L);
        assertThat(out.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(out.newAuthEpoch()).isEqualTo(6L);
        assertThat(out.requireLogin()).isTrue();
    }

    @Test
    void replacesOldBindingAndCreatesNextActiveBinding() {
        ChangeCustomerPhoneResult out = service.change(command("idem-binding", NEW_PHONE, "{}"));

        List<CustomerPhoneBindingHistoryEntity> bindingRows = bindings(CUSTOMER_ID);
        assertThat(bindingRows).extracting("bindingVersion", "status")
                .containsExactly(tuple(1L, "REPLACED"), tuple(2L, "ACTIVE"));
        assertThat(bindingRows.get(0).getValidTo()).isNotNull();
        assertThat(bindingRows.get(1).getChangeOperationNo()).isEqualTo(out.operationNo());
        assertThat(bindingRows.get(1).getPhoneCiphertext()).containsExactly(NEW_PHONE.getBytes(StandardCharsets.UTF_8));
        assertThat(bindingRows.get(1).getPhoneIdentityHash()).hasSize(64);
    }

    @Test
    void createsCompletedOperationStepsEventsAndOutboxes() {
        ChangeCustomerPhoneResult out = service.change(command("idem-runtime", NEW_PHONE, "{}"));

        Map<String, Object> operation = jdbc.queryForMap("""
                SELECT status, applied_lifecycle_version, completed_at, row_version
                FROM account_lifecycle_operation WHERE operation_no = ?
                """, out.operationNo());
        assertThat(operation).containsEntry("status", "COMPLETED")
                .containsEntry("applied_lifecycle_version", 4L)
                .containsEntry("row_version", 2L);
        assertThat(operation.get("completed_at")).isNotNull();
        assertThat(jdbc.queryForList("SELECT status FROM account_lifecycle_step WHERE operation_id = ?",
                out.operationId())).allSatisfy(row -> assertThat(row).containsEntry("status", "SUCCEEDED"));
        assertThat(count("SELECT COUNT(*) FROM account_lifecycle_event WHERE operation_id = ?", out.operationId()))
                .isEqualTo(3L);
        assertThat(count("SELECT COUNT(*) FROM account_lifecycle_outbox WHERE operation_id = ?", out.operationId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM account_lifecycle_outbox
                WHERE operation_id = ? AND topic = 'account.lifecycle.phone-changed.v1'
                """, Long.class, out.operationId())).isEqualTo(1L);
    }

    @Test
    void replayReturnsCompletedResultBeforeCustomerAndOtpChecks() {
        ChangeCustomerPhoneResult first = service.change(command("idem-replay", NEW_PHONE, "{\"source\":\"app\"}"));
        ChangeCustomerPhoneResult replay = service.change(command("idem-replay", NEW_PHONE, "{\"source\":\"app\"}"));

        assertThat(replay).isEqualTo(first);
        verify(otp, times(1)).consume(eq(OtpPurpose.PHONE_CHANGE_NEW_PHONE), any(), any());
    }

    @Test
    void sameKeyDifferentHashConflictsBeforeOtp() {
        service.change(command("idem-conflict", NEW_PHONE, "{\"reason\":\"a\"}"));

        assertThatThrownBy(() -> service.change(command("idem-conflict", "13900270002", "{\"reason\":\"b\"}")))
                .isInstanceOf(LifecycleOperationConflictException.class);
        verify(otp, times(1)).consume(eq(OtpPurpose.PHONE_CHANGE_NEW_PHONE), any(), any());
    }

    @Test
    void requestHashIncludesNewPhoneButExcludesOtpTraceAndTime() {
        ChangeCustomerPhoneCommand first = command("idem-hash", NEW_PHONE, "{\"z\":2,\"a\":1}");
        ChangeCustomerPhoneCommand replay = new ChangeCustomerPhoneCommand(CUSTOMER_ID, 3L, NEW_PHONE, "expired",
                "idem-hash", Long.toString(CUSTOMER_ID), "other-trace", " { \"a\" : 1, \"z\" : 2 } ",
                Instant.parse("2030-01-01T00:00:00Z"));
        ChangeCustomerPhoneCommand anotherPhone = new ChangeCustomerPhoneCommand(CUSTOMER_ID, 3L,
                "13900270002", "expired", "idem-hash", Long.toString(CUSTOMER_ID), "other-trace",
                "{\"a\":1,\"z\":2}", replay.requestedAt());

        assertThat(serviceRequestHash(first)).isEqualTo(serviceRequestHash(replay)).hasSize(64);
        assertThat(serviceRequestHash(anotherPhone)).isNotEqualTo(serviceRequestHash(first));
    }

    @Test
    void rejectsInvalidJsonAndSchemaLengthOverflowBeforeOtp() {
        assertThatThrownBy(() -> service.change(command("idem-json", NEW_PHONE, "{} {}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> new ChangeCustomerPhoneCommand(CUSTOMER_ID, 3L, NEW_PHONE, "111111",
                "i".repeat(129), "actor", "trace", "{}", requestedAt()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> new ChangeCustomerPhoneCommand(CUSTOMER_ID, 3L, NEW_PHONE, "111111",
                "idem", "a".repeat(65), "trace", "{}", requestedAt()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actorId");
        assertThatThrownBy(() -> new ChangeCustomerPhoneCommand(CUSTOMER_ID, 3L, NEW_PHONE, "111111",
                "idem", "actor", "t".repeat(65), "{}", requestedAt()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("traceId");
        verifyNoInteractions(otp);
    }

    @Test
    void rejectsSamePhoneAndOccupiedPhoneBeforeOtp() {
        insertCustomer(CUSTOMER_ID + 1, NEW_PHONE, 0L, 0L);

        assertThatThrownBy(() -> service.change(command("idem-same", OLD_PHONE, "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same");
        assertThatThrownBy(() -> service.change(command("idem-occupied", NEW_PHONE, "{}")))
                .isInstanceOf(LifecycleOperationConflictException.class).hasMessageContaining("phone");
        verifyNoInteractions(otp);
    }

    @Test
    void lifecycleVersionZeroIsValid() {
        cleanRows();
        insertCustomer(CUSTOMER_ID, OLD_PHONE, 0L, 5L);
        insertActiveBinding(CUSTOMER_ID, 1L, OLD_PHONE);

        assertThat(service.change(new ChangeCustomerPhoneCommand(CUSTOMER_ID, 0L, NEW_PHONE, "111111",
                "idem-zero", Long.toString(CUSTOMER_ID), "trace", "{}", requestedAt())).appliedLifecycleVersion())
                .isEqualTo(1L);
    }

    @Test
    void suspendsAmbientTransactionForReadsAndOtpThenStartsDatabaseTransaction() {
        AtomicBoolean lookupOutside = new AtomicBoolean();
        AtomicBoolean otpOutside = new AtomicBoolean();
        AtomicBoolean casInside = new AtomicBoolean();
        doAnswer(invocation -> {
            lookupOutside.set(!TransactionSynchronizationManager.isActualTransactionActive());
            return java.util.Optional.empty();
        }).when(snapshots).findByIdempotency(anyLong(), any(), any());
        when(otp.consume(eq(OtpPurpose.PHONE_CHANGE_NEW_PHONE), any(), any())).thenAnswer(invocation -> {
            otpOutside.set(!TransactionSynchronizationManager.isActualTransactionActive());
            return OtpConsumeResult.CONSUMED;
        });
        doAnswer(invocation -> {
            casInside.set(TransactionSynchronizationManager.isActualTransactionActive());
            return jdbc.update("""
                    UPDATE customer_phone_binding_history
                    SET status = 'REPLACED', valid_to = ?, change_operation_no = ?, updated_at = ?
                    WHERE customer_id = ? AND status = 'ACTIVE'
                    """, invocation.getArgument(2), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(0));
        }).when(histories).replaceActive(anyLong(), any(), any());

        outerTransactions.executeWithoutResult(status -> service.change(command("idem-ambient", NEW_PHONE, "{}")));

        assertThat(lookupOutside).isTrue();
        assertThat(otpOutside).isTrue();
        assertThat(casInside).isTrue();
    }

    @Test
    void duplicatePhoneRaceMapsToDomainConflictAndRollsBackButOtpStaysConsumed() {
        doThrow(new DuplicateKeyException("uk_customer_phone_active"))
                .when(histories).replaceActive(anyLong(), any(), any());

        assertThatThrownBy(() -> service.change(command("idem-duplicate", NEW_PHONE, "{}")))
                .isInstanceOf(LifecycleOperationConflictException.class).hasMessageContaining("phone");
        assertEverythingRolledBack();
        verify(otp).consume(eq(OtpPurpose.PHONE_CHANGE_NEW_PHONE), any(), any());
    }

    @Test
    void historyEventAndOutboxFailuresEachRollbackAllMysqlButNeverRestoreOtp() {
        assertRollbackOn("idem-history-fail", () -> doThrow(new IllegalStateException("history unavailable"))
                .when(histories).replaceActive(anyLong(), any(), any()));
        assertRollbackOn("idem-event-fail", () -> doThrow(new IllegalStateException("event unavailable"))
                .when(events).insert(any(LifecycleEventEntity.class)));
        assertRollbackOn("idem-outbox-fail", () -> doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxes).insert(any(LifecycleOutboxEntity.class)));
        verify(otp, times(3)).consume(eq(OtpPurpose.PHONE_CHANGE_NEW_PHONE), any(), any());
        verify(otp, never()).store(any(), any(), any(), any());
    }

    @Test
    void phoneChangeAndCancellationUsingSameExpectedVersionAllowOnlyOneSuccess() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> phoneChange = executor.submit(() -> runAfter(start, ready,
                    () -> service.change(command("idem-cross-change", NEW_PHONE, "{}"))));
            Future<Object> cancel = executor.submit(() -> runAfter(start, ready,
                    () -> cancellation.fence(new FenceAccountCancellationCommand(CUSTOMER_ID, 3L, "111111",
                            "idem-cross-cancel", Long.toString(CUSTOMER_ID), "trace", "{}", requestedAt()))));
            ready.await();
            start.countDown();
            List<Object> results = List.of(phoneChange.get(), cancel.get());

            assertThat(results.stream().filter(result -> !(result instanceof RuntimeException))).hasSize(1);
            assertThat(results.stream().filter(LifecycleOperationConflictException.class::isInstance)).hasSize(1);
            assertThat(customers.selectById(CUSTOMER_ID).getLifecycleVersion()).isEqualTo(4L);
            assertThat(count("SELECT COUNT(*) FROM account_lifecycle_operation WHERE customer_id = ?", CUSTOMER_ID))
                    .isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertRollbackOn(String key, Runnable failure) {
        failure.run();
        assertThatThrownBy(() -> service.change(command(key, NEW_PHONE, "{}")))
                .isInstanceOf(RuntimeException.class);
        assertEverythingRolledBack();
        reset(histories, events, outboxes, snapshots);
    }

    private void assertEverythingRolledBack() {
        Customer customer = customers.selectById(CUSTOMER_ID);
        assertThat(customer.getPhone()).isEqualTo(OLD_PHONE);
        assertThat(customer.getLifecycleVersion()).isEqualTo(3L);
        assertThat(customer.getAuthEpoch()).isEqualTo(5L);
        assertThat(bindings(CUSTOMER_ID)).extracting("bindingVersion", "status")
                .containsExactly(tuple(1L, "ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM account_lifecycle_operation WHERE customer_id = ?", CUSTOMER_ID))
                .isZero();
    }

    private ChangeCustomerPhoneCommand command(String key, String phone, String context) {
        return new ChangeCustomerPhoneCommand(CUSTOMER_ID, 3L, phone, "111111", key,
                Long.toString(CUSTOMER_ID), "trace-27001", context, requestedAt());
    }

    private String serviceRequestHash(ChangeCustomerPhoneCommand command) {
        return new com.sx.passenger.lifecycle.application.LifecycleRequestHasher().hash(command);
    }

    private Instant requestedAt() {
        return Instant.parse("2026-07-22T09:00:00Z");
    }

    private Object runAfter(CountDownLatch start, CountDownLatch ready, java.util.concurrent.Callable<Object> call)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return call.call();
        } catch (RuntimeException ex) {
            return ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<CustomerPhoneBindingHistoryEntity> bindings(long customerId) {
        return histories.selectList(Wrappers.<CustomerPhoneBindingHistoryEntity>lambdaQuery()
                .eq(CustomerPhoneBindingHistoryEntity::getCustomerId, customerId)
                .orderByAsc(CustomerPhoneBindingHistoryEntity::getBindingVersion));
    }

    private void insertCustomer(long id, String phone, long lifecycleVersion, long authEpoch) {
        jdbc.update("""
                INSERT INTO customer (id, phone, status, lifecycle_status, lifecycle_version, auth_epoch, is_deleted)
                VALUES (?, ?, 0, 'ACTIVE', ?, ?, 0)
                """, id, phone, lifecycleVersion, authEpoch);
    }

    private void insertActiveBinding(long id, long version, String phone) {
        jdbc.update("""
                INSERT INTO customer_phone_binding_history
                    (customer_id, binding_version, status, phone_ciphertext, phone_identity_hash,
                     hash_key_version, change_reason, valid_from, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, 'legacy-v1', 'REGISTER', ?, ?, ?)
                """, id, version, phone.getBytes(StandardCharsets.UTF_8), "a".repeat(64),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private long count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }

    private void cleanRows() {
        jdbc.update("DELETE FROM account_lifecycle_outbox WHERE operation_id IN (SELECT id FROM account_lifecycle_operation WHERE customer_id = ?)", CUSTOMER_ID);
        jdbc.update("DELETE FROM account_lifecycle_event WHERE customer_id = ?", CUSTOMER_ID);
        jdbc.update("DELETE FROM account_lifecycle_step WHERE operation_id IN (SELECT id FROM account_lifecycle_operation WHERE customer_id = ?)", CUSTOMER_ID);
        jdbc.update("DELETE FROM account_lifecycle_operation WHERE customer_id = ?", CUSTOMER_ID);
        jdbc.update("DELETE FROM customer_phone_binding_history WHERE customer_id = ?", CUSTOMER_ID);
        jdbc.update("DELETE FROM customer WHERE id IN (?, ?)", CUSTOMER_ID, CUSTOMER_ID + 1);
    }
}
