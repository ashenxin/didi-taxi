package com.sx.calculate.service;

import com.sx.calculate.dao.BenefitPointsAccountMapper;
import com.sx.calculate.dao.BenefitPointsFlowMapper;
import com.sx.calculate.dao.BenefitSignRecordMapper;
import com.sx.calculate.config.BenefitSignInProperties;
import com.sx.calculate.model.BenefitPointsAccount;
import com.sx.calculate.model.BenefitPointsFlow;
import com.sx.calculate.model.BenefitSignRecord;
import com.sx.calculate.model.dto.BenefitClearPointsRequest;
import com.sx.calculate.model.dto.BenefitSignInResult;
import com.sx.calculate.lifecycle.service.CalculateAccountWriteFence;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class BenefitServiceTest {
    private static final long CUSTOMER_ID = 10001L;
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    @Autowired
    private BenefitService benefitService;
    @Autowired
    private BenefitSignRecordMapper signRecordMapper;
    @Autowired
    private BenefitPointsAccountMapper accountMapper;
    @Autowired
    private BenefitPointsFlowMapper flowMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM benefit_points_flow");
        jdbcTemplate.update("DELETE FROM benefit_sign_record");
        jdbcTemplate.update("DELETE FROM benefit_points_account");
        reset(redisTemplate);
    }

    @Test
    void signInCreatesRecordFlowAndAccountPoints() {
        mockRedisSignResult(1L);

        BenefitSignInResult result = benefitService.signIn(CUSTOMER_ID, "req-first");

        assertThat(result.getNewSigned()).isTrue();
        assertThat(result.getRewardPoints()).isEqualTo(5);
        assertThat(result.getAvailablePoints()).isEqualTo(5);

        List<BenefitSignRecord> records = signRecordMapper.selectList(null);
        assertThat(records).hasSize(1);
        BenefitSignRecord record = records.get(0);
        assertThat(record.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(record.getRewardRuleCode()).isEqualTo("SIGN_IN_DAILY");
        assertThat(record.getPointsFlowId()).isNotNull();

        List<BenefitPointsFlow> flows = flowMapper.selectList(null);
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).getPointsDelta()).isEqualTo(5);
        assertThat(flows.get(0).getBalanceBefore()).isZero();
        assertThat(flows.get(0).getBalanceAfter()).isEqualTo(5);

        BenefitPointsAccount account = accountMapper.selectByCustomerIdForUpdate(CUSTOMER_ID);
        assertThat(account.getAvailablePoints()).isEqualTo(5);
        assertThat(account.getTotalEarnedPoints()).isEqualTo(5);
        assertThat(account.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void duplicateSignInDoesNotGrantPointsAgain() {
        mockRedisSignResult(1L);
        benefitService.signIn(CUSTOMER_ID, "req-first");
        mockRedisSignResult(0L);

        BenefitSignInResult duplicate = benefitService.signIn(CUSTOMER_ID, "req-second");

        assertThat(duplicate.getNewSigned()).isFalse();
        assertThat(duplicate.getRewardPoints()).isZero();
        assertThat(duplicate.getAvailablePoints()).isEqualTo(5);
        assertThat(signRecordMapper.selectList(null)).hasSize(1);
        assertThat(flowMapper.selectList(null)).hasSize(1);
        assertThat(accountMapper.selectByCustomerIdForUpdate(CUSTOMER_ID).getAvailablePoints()).isEqualTo(5);
    }

    @Test
    void redisSignedButMysqlMissingShouldRepairByCreatingSignInRecords() {
        mockRedisSignResult(0L);

        BenefitSignInResult result = benefitService.signIn(CUSTOMER_ID, "req-repair");

        assertThat(result.getNewSigned()).isTrue();
        assertThat(result.getRewardPoints()).isEqualTo(5);
        assertThat(result.getAvailablePoints()).isEqualTo(5);
        assertThat(signRecordMapper.selectList(null)).hasSize(1);
        assertThat(flowMapper.selectList(null)).hasSize(1);
        assertThat(accountMapper.selectByCustomerIdForUpdate(CUSTOMER_ID).getAvailablePoints()).isEqualTo(5);
    }

    @Test
    void seventhContinuousSignInUsesContinuousRewardOnly() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assumeTrue(today.getDayOfMonth() >= 7, "当前业务日期不足以构造当月连续 7 天签到");
        seedPreviousContinuousDays(today, 6);
        mockRedisSignResult(1L);

        BenefitSignInResult result = benefitService.signIn(CUSTOMER_ID, "req-seven");

        assertThat(result.getContinuousDays()).isEqualTo(7);
        assertThat(result.getRewardRuleCode()).isEqualTo("SIGN_IN_CONTINUOUS_7");
        assertThat(result.getRewardPoints()).isEqualTo(35);
        assertThat(result.getAvailablePoints()).isEqualTo(35);
        assertThat(signRecordMapper.selectList(null)).hasSize(7);
        assertThat(flowMapper.selectList(null)).hasSize(1);
    }

    @Test
    void clearPointsForAccountCancelClearsBalanceOnceAndIsIdempotent() {
        mockRedisSignResult(1L);
        benefitService.signIn(CUSTOMER_ID, "req-first");

        BenefitClearPointsRequest request = new BenefitClearPointsRequest()
                .setCustomerId(CUSTOMER_ID)
                .setCancelRequestId("cancel-1");
        benefitService.clearPointsForAccountCancel(request);
        benefitService.clearPointsForAccountCancel(request);

        BenefitPointsAccount account = accountMapper.selectByCustomerIdForUpdate(CUSTOMER_ID);
        assertThat(account.getStatus()).isEqualTo("CANCELLED");
        assertThat(account.getAvailablePoints()).isZero();
        assertThat(account.getTotalClearedPoints()).isEqualTo(5);

        List<BenefitPointsFlow> flows = flowMapper.selectList(null);
        assertThat(flows).hasSize(2);
        assertThat(flows).extracting(BenefitPointsFlow::getBizType)
                .containsExactlyInAnyOrder("SIGN_IN_DAILY", "ACCOUNT_CANCEL_CLEAR");
    }

    private void seedPreviousContinuousDays(LocalDate today, int days) {
        for (int i = days; i >= 1; i--) {
            LocalDate signDate = today.minusDays(i);
            BenefitSignRecord record = new BenefitSignRecord()
                    .setCustomerId(CUSTOMER_ID)
                    .setSignDate(signDate)
                    .setSignYearMonth(today.format(YEAR_MONTH))
                    .setDayOfMonth(signDate.getDayOfMonth())
                    .setBitmapOffset(signDate.getDayOfMonth() - 1)
                    .setContinuousDays(days - i + 1)
                    .setRewardPoints(5)
                    .setRewardRuleCode("SIGN_IN_DAILY")
                    .setRewardSnapshot("{\"ruleCode\":\"SIGN_IN_DAILY\",\"points\":5}")
                    .setSourceType("APP")
                    .setRequestId("seed-" + i)
                    .setCreatedAt(signDate.atStartOfDay())
                    .setUpdatedAt(signDate.atStartOfDay());
            signRecordMapper.insert(record);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockRedisSignResult(Long result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(result);
    }

    @Nested
    class AccountCreateRaceTest {
        @Test
        void overviewWithInvalidDisplayDaysReturnsDisabledCalendarWithoutDateOverflow() {
            BenefitSignRecordMapper signRecordMapper = mock(BenefitSignRecordMapper.class);
            BenefitPointsAccountMapper accountMapper = mock(BenefitPointsAccountMapper.class);
            BenefitPointsFlowMapper flowMapper = mock(BenefitPointsFlowMapper.class);
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            BenefitSignInProperties props = properties();
            props.setDisplayDays(32);

            BenefitService service = new BenefitService(
                    signRecordMapper, accountMapper, flowMapper, redisTemplate, props,
                    mock(CalculateAccountWriteFence.class));

            assertThatCode(() -> service.overview(CUSTOMER_ID)).doesNotThrowAnyException();
            assertThat(service.overview(CUSTOMER_ID).getSignEnabled()).isFalse();
            assertThat(service.overview(CUSTOMER_ID).getDays()).hasSizeLessThanOrEqualTo(31);
        }

        @Test
        void duplicateAccountCreateFallsBackToLockedReadAndDuplicateSignRecordHandling() {
            BenefitSignRecordMapper signRecordMapper = mock(BenefitSignRecordMapper.class);
            BenefitPointsAccountMapper accountMapper = mock(BenefitPointsAccountMapper.class);
            BenefitPointsFlowMapper flowMapper = mock(BenefitPointsFlowMapper.class);
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            BenefitService racedService = new BenefitService(
                    signRecordMapper, accountMapper, flowMapper, redisTemplate, properties(),
                    mock(CalculateAccountWriteFence.class));

            BenefitPointsAccount existingAccount = new BenefitPointsAccount()
                    .setId(10L)
                    .setCustomerId(CUSTOMER_ID)
                    .setAvailablePoints(5)
                    .setTotalEarnedPoints(5)
                    .setTotalUsedPoints(0)
                    .setTotalClearedPoints(0)
                    .setStatus("ACTIVE")
                    .setVersion(1);
            when(signRecordMapper.selectOne(any())).thenReturn(null);
            when(accountMapper.selectByCustomerIdForUpdate(CUSTOMER_ID))
                    .thenReturn(null)
                    .thenReturn(existingAccount);
            doAnswer(invocation -> {
                throw new DuplicateKeyException("uk_points_account_customer");
            }).when(accountMapper).insert(any(BenefitPointsAccount.class));
            doAnswer(invocation -> {
                throw new DuplicateKeyException("uk_sign_customer_date");
            }).when(signRecordMapper).insert(any(BenefitSignRecord.class));

            BenefitSignInResult result = racedService.signIn(CUSTOMER_ID, "req-race");

            assertThat(result.getNewSigned()).isFalse();
            assertThat(result.getMessage()).isEqualTo("今日已签到");
            assertThat(result.getAvailablePoints()).isEqualTo(5);
        }

        private BenefitSignInProperties properties() {
            BenefitSignInProperties props = new BenefitSignInProperties();
            props.setEnabled(true);
            props.setTimezone("Asia/Shanghai");
            props.setDisplayDays(28);
            BenefitSignInProperties.SignDays signDays = new BenefitSignInProperties.SignDays();
            signDays.setStartDayOfMonth(1);
            signDays.setEndDayOfMonth(28);
            props.setSignDays(signDays);
            BenefitSignInProperties.DefaultReward defaultReward = new BenefitSignInProperties.DefaultReward();
            defaultReward.setPoints(5);
            BenefitSignInProperties.Rewards rewards = new BenefitSignInProperties.Rewards();
            rewards.setDefault(defaultReward);
            BenefitSignInProperties.ContinuousReward continuousReward = new BenefitSignInProperties.ContinuousReward();
            continuousReward.setEveryDays(7);
            continuousReward.setPoints(35);
            continuousReward.setIncludeDefault(false);
            rewards.setContinuous(List.of(continuousReward));
            props.setRewards(rewards);
            return props;
        }
    }
}
