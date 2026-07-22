package com.sx.passenger.auth.session;

import com.sx.passenger.app.dto.AppAuthCustomerBrief;
import com.sx.passenger.dao.CustomerEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PassengerAuthEpochServiceIntegrationTest {

    private static final long ACTIVE_CUSTOMER_ID = 21_001L;
    private static final long CANCELLING_CUSTOMER_ID = 21_002L;
    private static final long CANCELLED_CUSTOMER_ID = 21_003L;
    private static final long DELETED_CUSTOMER_ID = 21_004L;

    @Autowired
    private PassengerAuthEpochService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerEntityMapper customerMapper;

    @BeforeEach
    void setUp() {
        insertCustomer(ACTIVE_CUSTOMER_ID, "13800021001", "ACTIVE", 1L, null, 0);
        insertCustomer(CANCELLING_CUSTOMER_ID, "13800021002", "CANCELLING", 5L, "op-cancel-1", 0);
        insertCustomer(CANCELLED_CUSTOMER_ID, "13800021003", "CANCELLED", 8L, null, 0);
        insertCustomer(DELETED_CUSTOMER_ID, "13800021004", "ACTIVE", 4L, null, 1);
        jdbcTemplate.update("""
                INSERT INTO account_lifecycle_operation (
                    operation_no, customer_id, operation_type, status, idempotency_key, request_hash,
                    expected_lifecycle_version, plan_code, plan_version, plan_digest, requested_at
                ) VALUES (?, ?, 'ACCOUNT_CANCEL', 'FENCED', 'idem-cancel-1', ?, 3,
                          'cancel-plan', 1, ?, CURRENT_TIMESTAMP)
                """, "op-cancel-1", CANCELLING_CUSTOMER_ID, "a".repeat(64), "b".repeat(64));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM account_lifecycle_operation WHERE customer_id BETWEEN ? AND ?",
                ACTIVE_CUSTOMER_ID, DELETED_CUSTOMER_ID);
        jdbcTemplate.update("DELETE FROM customer WHERE id BETWEEN ? AND ?",
                ACTIVE_CUSTOMER_ID, DELETED_CUSTOMER_ID);
    }

    @Test
    void activeAuthenticationBumpsEpochAndReturnsNormal() {
        AppAuthCustomerBrief out = service.completeAuthentication(ACTIVE_CUSTOMER_ID);

        assertThat(out.getAuthEpoch()).isEqualTo(2L);
        assertThat(out.getScope()).isEqualTo("NORMAL");
        assertThat(out.getOperationNo()).isNull();
    }

    @Test
    void cancellingAuthenticationBumpsBothCustomerAndOperationEpoch() {
        AppAuthCustomerBrief out = service.completeAuthentication(CANCELLING_CUSTOMER_ID);

        assertThat(out.getAuthEpoch()).isEqualTo(6L);
        assertThat(out.getScope()).isEqualTo("LIFECYCLE_RESTRICTED");
        assertThat(out.getOperationNo()).isEqualTo("op-cancel-1");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT restricted_auth_epoch
                FROM account_lifecycle_operation
                WHERE operation_no = 'op-cancel-1'
                """, Long.class)).isEqualTo(out.getAuthEpoch());
    }

    @Test
    void cancelledCustomerCannotAuthenticate() {
        assertThatThrownBy(() -> service.completeAuthentication(CANCELLED_CUSTOMER_ID))
                .isInstanceOf(AuthStateRejectedException.class);
    }

    @Test
    void staleLogoutCannotInvalidateNewerLogin() {
        long loginEpoch = service.completeAuthentication(ACTIVE_CUSTOMER_ID).getAuthEpoch();
        long newerEpoch = service.completeAuthentication(ACTIVE_CUSTOMER_ID).getAuthEpoch();

        assertThatThrownBy(() -> service.logout(ACTIVE_CUSTOMER_ID, loginEpoch))
                .isInstanceOf(AuthEpochConflictException.class);
        assertThat(service.loadState(ACTIVE_CUSTOMER_ID).authEpoch()).isEqualTo(newerEpoch);
    }

    @Test
    void phoneChangeCasBumpsEpochAndLifecycleVersionInSameUpdate() {
        int updated = customerMapper.changePhoneCas(
                ACTIVE_CUSTOMER_ID, "13900021001", 3L);

        assertThat(updated).isEqualTo(1);
        assertThat(customerRow(ACTIVE_CUSTOMER_ID))
                .containsEntry("phone", "13900021001")
                .containsEntry("auth_epoch", 2L)
                .containsEntry("lifecycle_version", 4L);
        assertThat(customerMapper.changePhoneCas(
                ACTIVE_CUSTOMER_ID, "13700021001", 3L)).isZero();
    }

    @Test
    void accountCancelCasBumpsEpochAndLifecycleVersionInSameUpdate() {
        LocalDateTime cancelledAt = LocalDateTime.of(2026, 7, 22, 2, 0);

        int updated = customerMapper.cancelAccountCas(ACTIVE_CUSTOMER_ID, 3L, cancelledAt);

        assertThat(updated).isEqualTo(1);
        assertThat(customerRow(ACTIVE_CUSTOMER_ID))
                .containsEntry("is_deleted", 1)
                .containsEntry("lifecycle_status", "CANCELLED")
                .containsEntry("auth_epoch", 2L)
                .containsEntry("lifecycle_version", 4L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancelled_at FROM customer WHERE id = ?", LocalDateTime.class, ACTIVE_CUSTOMER_ID))
                .isEqualTo(cancelledAt);
    }

    @Test
    void loadStateRejectsCancellingCustomerWhenBoundOperationIsTerminal() {
        jdbcTemplate.update("""
                UPDATE account_lifecycle_operation
                SET status = 'COMPLETED'
                WHERE operation_no = 'op-cancel-1'
                """);

        AuthoritativeAuthState state = service.loadState(CANCELLING_CUSTOMER_ID);

        assertThat(state.allowed()).isFalse();
        assertThat(state.allowedScope()).isNull();
    }

    @Test
    void loadStateRejectsCancellingCustomerWhenBoundOperationStatusIsUnknown() {
        jdbcTemplate.update("""
                UPDATE account_lifecycle_operation
                SET status = 'UNKNOWN'
                WHERE operation_no = 'op-cancel-1'
                """);

        AuthoritativeAuthState state = service.loadState(CANCELLING_CUSTOMER_ID);

        assertThat(state.allowed()).isFalse();
        assertThat(state.allowedScope()).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancellingAuthenticationRollsBackEpochWhenOperationUpdateConflicts() {
        jdbcTemplate.update("""
                UPDATE account_lifecycle_operation
                SET status = 'COMPLETED'
                WHERE operation_no = 'op-cancel-1'
                """);

        assertThatThrownBy(() -> service.completeAuthentication(CANCELLING_CUSTOMER_ID))
                .isInstanceOf(AuthStateRejectedException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT auth_epoch FROM customer WHERE id = ?", Long.class, CANCELLING_CUSTOMER_ID))
                .isEqualTo(5L);
    }

    @Test
    void loadStateMapsMissingDeletedAndCancelledCustomersToRejected() {
        assertThat(service.loadState(99_999L).allowed()).isFalse();
        assertThat(service.loadState(DELETED_CUSTOMER_ID).allowed()).isFalse();
        assertThat(service.loadState(CANCELLED_CUSTOMER_ID).allowed()).isFalse();
    }

    @Test
    void matchingLogoutBumpsEpochOnce() {
        long loggedOutEpoch = service.logout(ACTIVE_CUSTOMER_ID, 1L);

        assertThat(loggedOutEpoch).isEqualTo(2L);
        assertThat(service.loadState(ACTIVE_CUSTOMER_ID).authEpoch()).isEqualTo(2L);
    }

    private Map<String, Object> customerRow(long customerId) {
        return jdbcTemplate.queryForMap("""
                SELECT phone, is_deleted, lifecycle_status, lifecycle_version, auth_epoch
                FROM customer
                WHERE id = ?
                """, customerId);
    }

    private void insertCustomer(long id, String phone, String lifecycleStatus, long authEpoch,
                                String operationNo, int deleted) {
        jdbcTemplate.update("""
                INSERT INTO customer (
                    id, phone, status, lifecycle_status, lifecycle_version, auth_epoch,
                    current_lifecycle_operation_no, is_deleted
                ) VALUES (?, ?, 0, ?, 3, ?, ?, ?)
                """, id, phone, lifecycleStatus, authEpoch, operationNo, deleted);
    }
}
