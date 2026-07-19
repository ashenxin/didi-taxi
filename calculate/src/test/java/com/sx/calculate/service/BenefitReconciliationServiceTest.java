package com.sx.calculate.service;

import com.sx.calculate.model.BenefitReconciliationIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class BenefitReconciliationServiceTest {
    private static final long CUSTOMER_ID = 10001L;
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    @Autowired
    private BenefitReconciliationService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM benefit_reconciliation_issue");
        jdbcTemplate.update("DELETE FROM benefit_points_flow");
        jdbcTemplate.update("DELETE FROM benefit_sign_record");
        jdbcTemplate.update("DELETE FROM benefit_points_account");
        reset(redisTemplate, valueOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void rejectsOutOfRangePageSizeBeforeScanning() {
        assertThatThrownBy(() -> service.reconcile("{\"mode\":\"CUSTOMER\",\"customerId\":10001,\"pageSize\":49}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
    }

    @Test
    void currentMonthMissingBitmapBitIsRepairedWithoutChangingMysql() {
        LocalDate signDate = LocalDate.now().withDayOfMonth(1);
        seedConsistentSignIn(signDate, 5);
        String key = "benefit:sign:bitmap:" + CUSTOMER_ID + ":" + signDate.format(YEAR_MONTH);
        when(valueOperations.getBit(key, 0)).thenReturn(false);
        for (long offset = 1; offset < 28; offset++) {
            when(valueOperations.getBit(key, offset)).thenReturn(false);
        }

        BenefitReconciliationService.ReconciliationSummary result = service.reconcile(customerParam(signDate));

        assertThat(result.bitmapRepairedCount()).isEqualTo(1);
        assertThat(result.issueFoundCount()).isZero();
        verify(valueOperations).setBit(key, 0, true);
        assertThat(openIssues()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_points FROM benefit_points_account WHERE customer_id = ?",
                Integer.class, CUSTOMER_ID)).isEqualTo(5);
    }

    @Test
    void missingSignFlowIsRecordedButPointsAreNotChanged() {
        LocalDate signDate = LocalDate.now().withDayOfMonth(1);
        jdbcTemplate.update("""
                INSERT INTO benefit_sign_record
                (customer_id, sign_date, sign_year_month, day_of_month, bitmap_offset, continuous_days,
                 reward_points, reward_rule_code, points_flow_id, source_type, created_at, updated_at)
                VALUES (?, ?, ?, 1, 0, 1, 5, 'SIGN_IN_DAILY', NULL, 'APP', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID, signDate, signDate.format(YEAR_MONTH));
        jdbcTemplate.update("""
                INSERT INTO benefit_points_account
                (customer_id, available_points, total_earned_points, total_used_points, total_cleared_points,
                 status, last_sign_date, last_points_flow_id, version)
                VALUES (?, 5, 5, 0, 0, 'ACTIVE', ?, NULL, 1)
                """, CUSTOMER_ID, signDate);
        stubCurrentMonthBitmap(signDate, true);

        service.reconcile(customerParam(signDate));

        assertThat(openIssues()).extracting(BenefitReconciliationIssue::getIssueType)
                .contains("SIGN_FLOW_MISSING", "ACCOUNT_BALANCE_MISMATCH", "ACCOUNT_EARNED_MISMATCH");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_points FROM benefit_points_account WHERE customer_id = ?",
                Integer.class, CUSTOMER_ID)).isEqualTo(5);
    }

    @Test
    void issueIsResolvedAfterUnderlyingDataBecomesConsistent() {
        LocalDate signDate = LocalDate.now().withDayOfMonth(1);
        seedConsistentSignIn(signDate, 5);
        jdbcTemplate.update("UPDATE benefit_points_account SET available_points = 99 WHERE customer_id = ?", CUSTOMER_ID);
        stubCurrentMonthBitmap(signDate, true);

        service.reconcile(customerParam(signDate));
        assertThat(openIssues()).extracting(BenefitReconciliationIssue::getIssueType)
                .contains("ACCOUNT_BALANCE_MISMATCH");

        jdbcTemplate.update("UPDATE benefit_points_account SET available_points = 5 WHERE customer_id = ?", CUSTOMER_ID);
        service.reconcile(customerParam(signDate));

        assertThat(openIssues()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM benefit_reconciliation_issue WHERE issue_type='ACCOUNT_BALANCE_MISMATCH'",
                String.class)).isEqualTo("RESOLVED");
    }

    @Test
    void cancelledAccountWithBalanceIsRecordedWithoutAutomaticClearing() {
        LocalDate signDate = LocalDate.now().withDayOfMonth(1);
        jdbcTemplate.update("""
                INSERT INTO benefit_points_account
                (customer_id, available_points, total_earned_points, total_used_points, total_cleared_points,
                 status, last_sign_date, last_points_flow_id, version)
                VALUES (?, 8, 8, 0, 0, 'CANCELLED', NULL, NULL, 1)
                """, CUSTOMER_ID);
        stubCurrentMonthBitmap(signDate, false);

        service.reconcile(customerParam(signDate));

        assertThat(openIssues()).extracting(BenefitReconciliationIssue::getIssueType)
                .contains("CANCELLED_ACCOUNT_HAS_BALANCE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_points FROM benefit_points_account WHERE customer_id = ?",
                Integer.class, CUSTOMER_ID)).isEqualTo(8);
    }

    @Test
    void redisFailureIsPersistedAndReportedWithoutChangingPoints() {
        LocalDate signDate = LocalDate.now().withDayOfMonth(1);
        seedConsistentSignIn(signDate, 5);
        String key = "benefit:sign:bitmap:" + CUSTOMER_ID + ":" + signDate.format(YEAR_MONTH);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations).getBit(key, 0);

        BenefitReconciliationService.ReconciliationSummary result = service.reconcile(customerParam(signDate));

        assertThat(result.repairFailedCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("PARTIAL_FAILED");
        assertThat(openIssues()).extracting(BenefitReconciliationIssue::getIssueType)
                .contains("BITMAP_REPAIR_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_points FROM benefit_points_account WHERE customer_id = ?",
                Integer.class, CUSTOMER_ID)).isEqualTo(5);
    }

    @Test
    void closedMonthPersistFailureIsReportedInsteadOfLeavingExpiringOfficialBitmap() {
        LocalDate signDate = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        seedConsistentSignIn(signDate, 5);
        String key = "benefit:sign:bitmap:" + CUSTOMER_ID + ":" + signDate.format(YEAR_MONTH);
        when(redisTemplate.expire(startsWith(key + ":rebuild:"), eq(1L), eq(TimeUnit.HOURS)))
                .thenReturn(true);
        when(redisTemplate.persist(key)).thenReturn(false);
        when(valueOperations.getBit(key, 0)).thenReturn(true);
        for (long offset = 1; offset < 28; offset++) {
            when(valueOperations.getBit(key, offset)).thenReturn(false);
        }

        BenefitReconciliationService.ReconciliationSummary result = service.reconcile(customerParam(signDate));

        assertThat(result.repairFailedCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("PARTIAL_FAILED");
        assertThat(openIssues()).extracting(BenefitReconciliationIssue::getIssueType)
                .contains("BITMAP_REPAIR_FAILED");
    }

    @Test
    void closedMonthBitmapIsRebuiltAndMadePersistent() {
        LocalDate signDate = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        seedConsistentSignIn(signDate, 5);
        String key = "benefit:sign:bitmap:" + CUSTOMER_ID + ":" + signDate.format(YEAR_MONTH);
        when(redisTemplate.expire(startsWith(key + ":rebuild:"), eq(1L), eq(TimeUnit.HOURS)))
                .thenReturn(true);
        when(redisTemplate.persist(key)).thenReturn(true);
        when(valueOperations.getBit(key, 0)).thenReturn(true);
        for (long offset = 1; offset < 28; offset++) {
            when(valueOperations.getBit(key, offset)).thenReturn(false);
        }

        BenefitReconciliationService.ReconciliationSummary result = service.reconcile(customerParam(signDate));

        assertThat(result.bitmapRepairedCount()).isEqualTo(1);
        assertThat(result.repairFailedCount()).isZero();
        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(redisTemplate).rename(startsWith(key + ":rebuild:"), eq(key));
        verify(redisTemplate).persist(key);
        assertThat(openIssues()).isEmpty();
    }

    @Test
    void currentMonthExtraBitIsRecordedButNeverCleared() {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        jdbcTemplate.update("""
                INSERT INTO benefit_points_account
                (customer_id, available_points, total_earned_points, total_used_points, total_cleared_points,
                 status, last_sign_date, last_points_flow_id, version)
                VALUES (?, 0, 0, 0, 0, 'ACTIVE', NULL, NULL, 0)
                """, CUSTOMER_ID);
        String key = "benefit:sign:bitmap:" + CUSTOMER_ID + ":" + month.format(YEAR_MONTH);
        when(valueOperations.getBit(key, 0)).thenReturn(true);
        for (long offset = 1; offset < 28; offset++) {
            when(valueOperations.getBit(key, offset)).thenReturn(false);
        }

        service.reconcile(customerParam(month));

        assertThat(openIssues()).extracting(BenefitReconciliationIssue::getIssueType)
                .contains("BITMAP_EXTRA_BIT");
        verify(valueOperations, never()).setBit(key, 0, false);
    }

    @Test
    void repeatedScanUpdatesOccurrenceCountWithoutDuplicatingIssues() {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        jdbcTemplate.update("""
                INSERT INTO benefit_points_account
                (customer_id, available_points, total_earned_points, total_used_points, total_cleared_points,
                 status, last_sign_date, last_points_flow_id, version)
                VALUES (?, 9, 0, 0, 0, 'ACTIVE', NULL, NULL, 0)
                """, CUSTOMER_ID);
        stubCurrentMonthBitmap(month, false);

        service.reconcile(customerParam(month));
        service.reconcile(customerParam(month));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM benefit_reconciliation_issue WHERE customer_id=?",
                Integer.class, CUSTOMER_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT occurrence_count FROM benefit_reconciliation_issue WHERE customer_id=?",
                Integer.class, CUSTOMER_ID)).isEqualTo(2);
    }

    private void seedConsistentSignIn(LocalDate signDate, int points) {
        jdbcTemplate.update("""
                INSERT INTO benefit_points_account
                (id, customer_id, available_points, total_earned_points, total_used_points, total_cleared_points,
                 status, last_sign_date, version)
                VALUES (10, ?, ?, ?, 0, 0, 'ACTIVE', ?, 1)
                """, CUSTOMER_ID, points, points, signDate);
        jdbcTemplate.update("""
                INSERT INTO benefit_sign_record
                (id, customer_id, sign_date, sign_year_month, day_of_month, bitmap_offset, continuous_days,
                 reward_points, reward_rule_code, points_flow_id, source_type, created_at, updated_at)
                VALUES (20, ?, ?, ?, 1, 0, 1, ?, 'SIGN_IN_DAILY', 30, 'APP', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID, signDate, signDate.format(YEAR_MONTH), points);
        jdbcTemplate.update("""
                INSERT INTO benefit_points_flow
                (id, customer_id, account_id, biz_type, biz_id, points_delta, balance_before, balance_after,
                 flow_direction, sign_record_id, created_at)
                VALUES (30, ?, 10, 'SIGN_IN_DAILY', '20', ?, 0, ?, 'IN', 20, CURRENT_TIMESTAMP)
                """, CUSTOMER_ID, points, points);
        jdbcTemplate.update("UPDATE benefit_points_account SET last_points_flow_id = 30 WHERE id = 10");
    }

    private void stubCurrentMonthBitmap(LocalDate date, boolean firstBit) {
        String key = "benefit:sign:bitmap:" + CUSTOMER_ID + ":" + date.format(YEAR_MONTH);
        when(valueOperations.getBit(key, 0)).thenReturn(firstBit);
        for (long offset = 1; offset < 28; offset++) {
            when(valueOperations.getBit(key, offset)).thenReturn(false);
        }
    }

    private String customerParam(LocalDate date) {
        return "{\"mode\":\"CUSTOMER\",\"customerId\":" + CUSTOMER_ID
                + ",\"yearMonth\":\"" + date.format(YEAR_MONTH) + "\",\"pageSize\":200}";
    }

    private List<BenefitReconciliationIssue> openIssues() {
        return jdbcTemplate.query("""
                SELECT id, issue_key, issue_type, severity, customer_id, sign_date, year_month, reference_type,
                       reference_id, expected_snapshot, actual_snapshot, status, first_detected_at,
                       last_detected_at, resolved_at, occurrence_count, last_run_id, created_at, updated_at
                FROM benefit_reconciliation_issue WHERE status='OPEN' ORDER BY id
                """, (rs, rowNum) -> new BenefitReconciliationIssue()
                .setId(rs.getLong("id"))
                .setIssueKey(rs.getString("issue_key"))
                .setIssueType(rs.getString("issue_type"))
                .setSeverity(rs.getString("severity"))
                .setCustomerId(rs.getLong("customer_id"))
                .setStatus(rs.getString("status")));
    }
}
