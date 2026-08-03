package com.sx.passenger.lifecycle.application.cancel;

import com.sx.passenger.auth.otp.AtomicOtpService;
import com.sx.passenger.auth.otp.OtpConsumeResult;
import com.sx.passenger.auth.otp.OtpPurpose;
import com.sx.passenger.auth.otp.OtpSubject;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.lifecycle.application.LifecycleOperationConflictException;
import com.sx.passenger.lifecycle.application.LifecycleRequestHasher;
import com.sx.passenger.lifecycle.application.LifecycleSnapshotStore;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleEventMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import com.sx.passenger.model.Customer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AccountCancellationFenceServiceIntegrationTest {
    private static final long CUSTOMER_ID = 26_001L;
    private long baselineStepCount;
    private long baselineOutboxCount;

    @Autowired AccountCancellationFenceService service;
    @Autowired LifecycleRequestHasher hasher;
    @Autowired CustomerEntityMapper customers;
    @SpyBean LifecycleOperationMapper operations;
    @SpyBean LifecycleSnapshotStore snapshots;
    @Autowired LifecycleStepMapper steps;
    @SpyBean LifecycleEventMapper events;
    @SpyBean LifecycleOutboxMapper outboxes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate outerTransactions;
    @Autowired MeterRegistry meterRegistry;
    @MockBean AtomicOtpService otp;

    @BeforeEach
    void setUp() {
        reset(otp, operations, snapshots, events, outboxes);
        cleanRows();
        baselineStepCount = steps.selectCount(null);
        baselineOutboxCount = outboxes.selectCount(null);
        insertCustomer(CUSTOMER_ID, "13800260001", 0L, 7L);
        when(otp.consume(eq(OtpPurpose.ACCOUNT_CANCEL), any(OtpSubject.class), any()))
                .thenReturn(OtpConsumeResult.CONSUMED);
    }

    @AfterEach
    void tearDown() {
        reset(operations, snapshots, events, outboxes);
        cleanRows();
    }

    @Test
    void requestHashIsStableAndExcludesOtpTraceAndTime() {
        FenceAccountCancellationCommand first = command("idem-hash", "111111", "{\"z\":2,\"a\":1}");
        FenceAccountCancellationCommand replay = new FenceAccountCancellationCommand(
                first.customerId(), first.expectedLifecycleVersion(), "expired", first.idempotencyKey(),
                first.actorId(), "another-trace", " { \"a\" : 1, \"z\" : 2 } ",
                Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(replay)).hasSize(64);
    }

    @Test
    void rejectsTrailingJsonTokensBeforeOtp() {
        assertThatThrownBy(() -> service.fence(command("idem-invalid-json", "111111", "{} {}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");

        verifyNoInteractions(otp);
    }

    @Test
    void commandAcceptsSchemaLengthBoundariesAndRejectsOverflowBeforeOtp() {
        FenceAccountCancellationCommand boundary = new FenceAccountCancellationCommand(
                CUSTOMER_ID, 0L, "111111", "i".repeat(128), "a".repeat(64), "t".repeat(64),
                "{}", Instant.parse("2026-07-22T08:00:00Z"));
        assertThat(boundary.idempotencyKey()).hasSize(128);

        assertThatThrownBy(() -> new FenceAccountCancellationCommand(
                CUSTOMER_ID, 0L, "111111", "i".repeat(129), "actor", "trace", "{}", boundary.requestedAt()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> new FenceAccountCancellationCommand(
                CUSTOMER_ID, 0L, "111111", "idem", "a".repeat(65), "trace", "{}", boundary.requestedAt()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actorId");
        assertThatThrownBy(() -> new FenceAccountCancellationCommand(
                CUSTOMER_ID, 0L, "111111", "idem", "actor", "t".repeat(65), "{}", boundary.requestedAt()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("traceId");
        verifyNoInteractions(otp);
    }

    @Test
    void suspendsCallingTransactionForIdempotencyAndOtpThenStartsTransactionForDatabaseWrites() {
        AtomicBoolean idempotencyOutsideTransaction = new AtomicBoolean();
        AtomicBoolean otpOutsideTransaction = new AtomicBoolean();
        AtomicBoolean casInsideTransaction = new AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            idempotencyOutsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            return java.util.Optional.empty();
        }).when(snapshots).findByIdempotency(anyLong(), any(), any());
        when(otp.consume(eq(OtpPurpose.ACCOUNT_CANCEL), any(OtpSubject.class), any()))
                .thenAnswer(invocation -> {
                    otpOutsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
                    return OtpConsumeResult.CONSUMED;
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            casInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return jdbc.update("""
                    UPDATE account_lifecycle_operation
                    SET status = 'FENCED', restricted_auth_epoch = ?, applied_lifecycle_version = ?,
                        fenced_at = ?, row_version = row_version + 1, updated_at = ?
                    WHERE id = ? AND status = 'REQUESTED' AND row_version = ?
                    """, invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4),
                    invocation.getArgument(4), invocation.getArgument(0), invocation.getArgument(1));
        }).when(operations).fenceRequestedCas(anyLong(), anyLong(), anyLong(), anyLong(), any());

        outerTransactions.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            service.fence(command("idem-ambient-tx", "111111", "{}"));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });

        assertThat(idempotencyOutsideTransaction).isTrue();
        assertThat(otpOutsideTransaction).isTrue();
        assertThat(casInsideTransaction).isTrue();
    }

    @Test
    void lifecycleVersionZeroIsAValidInitialVersion() {
        AccountCancellationFenceResult result = service.fence(command("idem-zero", "111111", "{}"));

        assertThat(result.appliedLifecycleVersion()).isEqualTo(1L);
    }

    @Test
    void staleLifecycleVersionConflictsBeforeOtpConsumption() {
        jdbc.update("UPDATE customer SET lifecycle_version = 1 WHERE id = ?", CUSTOMER_ID);

        assertThatThrownBy(() -> service.fence(command("idem-stale", "111111", "{}")))
                .isInstanceOf(LifecycleOperationConflictException.class)
                .hasMessageContaining("version changed");

        verifyNoInteractions(otp);
    }

    @Test
    void sameIdempotencyAndHashReturnsExistingWithoutConsumingOtp() {
        AccountCancellationFenceResult first = service.fence(command("idem-replay", "111111", "{\"source\":\"app\"}"));
        AccountCancellationFenceResult replay = service.fence(command("idem-replay", "expired", "{\"source\":\"app\"}"));

        assertThat(replay).isEqualTo(first);
        verify(otp, times(1)).consume(eq(OtpPurpose.ACCOUNT_CANCEL), any(OtpSubject.class), any());
    }

    @Test
    void sameKeyDifferentHashConflictsBeforeOtp() {
        service.fence(command("idem-conflict", "111111", "{\"reason\":\"a\"}"));

        assertThatThrownBy(() -> service.fence(command("idem-conflict", "222222", "{\"reason\":\"b\"}")))
                .isInstanceOf(LifecycleOperationConflictException.class);
        verify(otp, times(1)).consume(eq(OtpPurpose.ACCOUNT_CANCEL), any(OtpSubject.class), any());
    }

    @Test
    void createsCompleteFenceSnapshotAtomically() {
        double successBefore = epochCount("success");
        double failureBefore = epochCount("failure");
        AccountCancellationFenceResult result = service.fence(command("idem-success", "111111", "{}"));

        Customer customer = customers.selectById(CUSTOMER_ID);
        LifecycleOperationEntity operation = operations.selectById(result.operationId());
        assertThat(customer.getLifecycleStatus()).isEqualTo("CANCELLING");
        assertThat(customer.getLifecycleVersion()).isEqualTo(1L);
        assertThat(customer.getAuthEpoch()).isEqualTo(8L);
        assertThat(customer.getCurrentLifecycleOperationNo()).isEqualTo(result.operationNo());
        assertThat(operation.getStatus()).isEqualTo("FENCED");
        assertThat(operation.getRestrictedAuthEpoch()).isEqualTo(8L);
        assertThat(operation.getAppliedLifecycleVersion()).isEqualTo(1L);
        assertThat(operation.getRowVersion()).isEqualTo(1L);
        assertThat(operation.getFencedAt()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM account_lifecycle_step WHERE operation_id = ?", result.operationId()))
                .isEqualTo(12L);
        assertThat(count("SELECT COUNT(*) FROM account_lifecycle_event WHERE operation_id = ?", result.operationId()))
                .isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM account_lifecycle_outbox WHERE operation_id = ?", result.operationId()))
                .isEqualTo(1L);
        assertThat(epochCount("success") - successBefore).isEqualTo(1);
        assertThat(epochCount("failure") - failureBefore).isZero();
    }

    @Test
    void eventInsertFailureRollsBackMysqlButOtpRemainsConsumed() {
        double successBefore = epochCount("success");
        double failureBefore = epochCount("failure");
        doThrow(new IllegalStateException("event unavailable")).when(events).insert(any(LifecycleEventEntity.class));

        assertThatThrownBy(() -> service.fence(command("idem-event-fail", "111111", "{}")))
                .isInstanceOf(IllegalStateException.class);

        assertEverythingRolledBack();
        verify(otp, times(1)).consume(eq(OtpPurpose.ACCOUNT_CANCEL), any(OtpSubject.class), any());
        assertThat(epochCount("success") - successBefore).isZero();
        assertThat(epochCount("failure") - failureBefore).isEqualTo(1);
    }

    @Test
    void outboxInsertFailureRollsBackMysqlButOtpRemainsConsumed() {
        doThrow(new IllegalStateException("outbox unavailable")).when(outboxes).insert(any(LifecycleOutboxEntity.class));

        assertThatThrownBy(() -> service.fence(command("idem-outbox-fail", "111111", "{}")))
                .isInstanceOf(IllegalStateException.class);

        assertEverythingRolledBack();
        verify(otp, times(1)).consume(eq(OtpPurpose.ACCOUNT_CANCEL), any(OtpSubject.class), any());
    }

    @Test
    void operationFenceCasCountMismatchRollsBackMysqlButOtpRemainsConsumed() {
        org.mockito.Mockito.doReturn(0).when(operations).fenceRequestedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());

        assertThatThrownBy(() -> service.fence(command("idem-operation-fail", "111111", "{}")))
                .isInstanceOf(LifecycleOperationConflictException.class);

        assertEverythingRolledBack();
        verify(otp, times(1)).consume(eq(OtpPurpose.ACCOUNT_CANCEL), any(OtpSubject.class), any());
    }

    @Test
    void competingLifecycleCommandsWithSameExpectedVersionAllowOnlyOneFence() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (String key : List.of("idem-race-a", "idem-race-b")) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return service.fence(command(key, "111111", "{\"key\":\"" + key + "\"}"));
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Object> outcomes = List.of(futures.get(0).get(), futures.get(1).get());

            assertThat(outcomes.stream().filter(AccountCancellationFenceResult.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(LifecycleOperationConflictException.class::isInstance)).hasSize(1);
            assertThat(operationCount()).isEqualTo(1L);
            assertThat(customers.selectById(CUSTOMER_ID).getLifecycleVersion()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void phoneChangeAndCancellationWithSameExpectedVersionAllowOnlyOneCustomerCas() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> cancellation = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return service.fence(command("idem-cross-race", "111111", "{}"));
                } catch (RuntimeException ex) {
                    return ex;
                }
            });
            Future<Integer> phoneChange = executor.submit(() -> {
                ready.countDown();
                start.await();
                return customers.changePhoneCas(CUSTOMER_ID, "13900260001", 0L);
            });
            ready.await();
            start.countDown();

            Object cancellationOutcome = cancellation.get();
            int phoneChangeCount = phoneChange.get();
            int successCount = (cancellationOutcome instanceof AccountCancellationFenceResult ? 1 : 0)
                    + phoneChangeCount;
            assertThat(successCount).isEqualTo(1);
            if (phoneChangeCount == 1) {
                assertThat(cancellationOutcome).isInstanceOf(LifecycleOperationConflictException.class);
                assertThat(operationCount()).isZero();
            } else {
                assertThat(cancellationOutcome).isInstanceOf(AccountCancellationFenceResult.class);
                assertThat(operationCount()).isEqualTo(1L);
            }
            assertThat(customers.selectById(CUSTOMER_ID).getLifecycleVersion()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private FenceAccountCancellationCommand command(String idempotencyKey, String code, String context) {
        return new FenceAccountCancellationCommand(CUSTOMER_ID, 0L, code, idempotencyKey,
                Long.toString(CUSTOMER_ID), "trace-26001", context,
                Instant.parse("2026-07-22T08:00:00Z"));
    }

    private void assertEverythingRolledBack() {
        Map<String, Object> customer = jdbc.queryForMap("""
                SELECT lifecycle_status, lifecycle_version, auth_epoch, current_lifecycle_operation_no
                FROM customer WHERE id = ?
                """, CUSTOMER_ID);
        assertThat(customer).containsEntry("lifecycle_status", "ACTIVE")
                .containsEntry("lifecycle_version", 0L)
                .containsEntry("auth_epoch", 7L);
        assertThat(customer.get("current_lifecycle_operation_no")).isNull();
        assertThat(operationCount()).isZero();
        assertThat(steps.selectCount(null)).isEqualTo(baselineStepCount);
        assertThat(count("SELECT COUNT(*) FROM account_lifecycle_event WHERE customer_id = ?", CUSTOMER_ID)).isZero();
        assertThat(outboxes.selectCount(null)).isEqualTo(baselineOutboxCount);
    }

    private void insertCustomer(long id, String phone, long lifecycleVersion, long authEpoch) {
        jdbc.update("""
                INSERT INTO customer (id, phone, status, lifecycle_status, lifecycle_version, auth_epoch, is_deleted)
                VALUES (?, ?, 0, 'ACTIVE', ?, ?, 0)
                """, id, phone, lifecycleVersion, authEpoch);
    }

    private long operationCount() {
        return count("SELECT COUNT(*) FROM account_lifecycle_operation WHERE customer_id = ?", CUSTOMER_ID);
    }

    private long count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }

    private double epochCount(String result) {
        var counter = meterRegistry.find("passenger.auth.epoch.bump")
                .tags("cause", "account_cancel", "result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private void cleanRows() {
        jdbc.update("DELETE FROM account_lifecycle_outbox WHERE operation_id IN (SELECT id FROM account_lifecycle_operation WHERE customer_id = ?)", CUSTOMER_ID);
        jdbc.update("DELETE FROM account_lifecycle_event WHERE customer_id = ?", CUSTOMER_ID);
        jdbc.update("DELETE FROM account_lifecycle_step WHERE operation_id IN (SELECT id FROM account_lifecycle_operation WHERE customer_id = ?)", CUSTOMER_ID);
        jdbc.update("DELETE FROM account_lifecycle_operation WHERE customer_id = ?", CUSTOMER_ID);
        jdbc.update("DELETE FROM customer WHERE id = ?", CUSTOMER_ID);
    }
}
