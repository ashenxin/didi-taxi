package com.sx.calculate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.calculate.config.BenefitSignInProperties;
import com.sx.calculate.dao.BenefitPointsAccountMapper;
import com.sx.calculate.dao.BenefitPointsFlowMapper;
import com.sx.calculate.dao.BenefitSignRecordMapper;
import com.sx.calculate.lifecycle.model.CalculateWriteAction;
import com.sx.calculate.lifecycle.service.CalculateAccountWriteFence;
import com.sx.calculate.model.BenefitPointsAccount;
import com.sx.calculate.model.BenefitPointsFlow;
import com.sx.calculate.model.BenefitSignRecord;
import com.sx.calculate.model.dto.BenefitClearPointsRequest;
import com.sx.calculate.model.dto.BenefitDayVO;
import com.sx.calculate.model.dto.BenefitClearPointsResult;
import com.sx.calculate.model.dto.BenefitOverviewVO;
import com.sx.calculate.model.dto.BenefitPointsVO;
import com.sx.calculate.model.dto.BenefitSignInResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class BenefitService {
    private static final String ACCOUNT_ACTIVE = "ACTIVE";
    private static final String ACCOUNT_CANCELLED = "CANCELLED";
    private static final String FLOW_IN = "IN";
    private static final String FLOW_OUT = "OUT";
    private static final String RULE_DAILY = "SIGN_IN_DAILY";
    private static final String RULE_CONTINUOUS_7 = "SIGN_IN_CONTINUOUS_7";
    private static final String BIZ_CANCEL_CLEAR = "ACCOUNT_CANCEL_CLEAR";
    private static final String DISABLED_MONTH_CLOSED = "MONTH_SIGN_CLOSED";
    private static final String DISABLED_CONFIG_ERROR = "CONFIG_ERROR";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DefaultRedisScript<Long> RECORD_SIGN_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SETBIT', KEYS[1], tonumber(ARGV[1]), 1)
            return 1
            """, Long.class);

    private final BenefitSignRecordMapper signRecordMapper;
    private final BenefitPointsAccountMapper accountMapper;
    private final BenefitPointsFlowMapper flowMapper;
    private final StringRedisTemplate redisTemplate;
    private final BenefitSignInProperties properties;
    private final CalculateAccountWriteFence accountWriteFence;

    public BenefitService(BenefitSignRecordMapper signRecordMapper,
                          BenefitPointsAccountMapper accountMapper,
                          BenefitPointsFlowMapper flowMapper,
                          StringRedisTemplate redisTemplate,
                          BenefitSignInProperties properties,
                          CalculateAccountWriteFence accountWriteFence) {
        this.signRecordMapper = signRecordMapper;
        this.accountMapper = accountMapper;
        this.flowMapper = flowMapper;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.accountWriteFence = accountWriteFence;
    }

    public BenefitOverviewVO overview(Long customerId) {
        validateCustomerId(customerId);
        LocalDate today = businessDate();
        String yearMonth = today.format(YEAR_MONTH);
        BenefitPointsVO points = points(customerId);
        BenefitOverviewVO out = baseOverview(today)
                .setAvailablePoints(points.getAvailablePoints());

        boolean configOk = isConfigValid(false);
        if (!configOk) {
            return out.setSignEnabled(false)
                    .setDisabledReason(DISABLED_CONFIG_ERROR)
                    .setSignedToday(false)
                    .setContinuousDays(0)
                    .setTodayRewardPoints(0)
                    .setTodayRewardRuleCode(null)
                    .setDays(monthDays(today, Collections.emptyMap()));
        }

        List<BenefitSignRecord> records = signRecordMapper.selectList(Wrappers.<BenefitSignRecord>lambdaQuery()
                .eq(BenefitSignRecord::getCustomerId, customerId)
                .eq(BenefitSignRecord::getSignYearMonth, yearMonth));
        Map<Integer, BenefitSignRecord> byDay = new HashMap<>();
        for (BenefitSignRecord item : records) {
            byDay.put(item.getDayOfMonth(), item);
        }
        BenefitSignRecord todayRecord = byDay.get(today.getDayOfMonth());
        boolean signedToday = todayRecord != null;
        int continuous = signedToday ? safeInt(todayRecord.getContinuousDays()) : countPreviousContinuous(byDay, today.getDayOfMonth()) + 1;
        RewardPlan plan = rewardPlan(continuous);
        boolean signEnabled = today.getDayOfMonth() >= signStartDay() && today.getDayOfMonth() <= signEndDay();

        out.setSignEnabled(signEnabled)
                .setDisabledReason(signEnabled ? null : DISABLED_MONTH_CLOSED)
                .setSignedToday(signedToday)
                .setContinuousDays(signedToday ? safeInt(todayRecord.getContinuousDays()) : Math.max(0, continuous - 1))
                .setTodayRewardPoints(signedToday ? safeInt(todayRecord.getRewardPoints()) : plan.points())
                .setTodayRewardRuleCode(signedToday ? todayRecord.getRewardRuleCode() : plan.ruleCode())
                .setDays(monthDays(today, byDay));
        return out;
    }

    public BenefitPointsVO points(Long customerId) {
        validateCustomerId(customerId);
        BenefitPointsAccount account = accountMapper.selectOne(Wrappers.<BenefitPointsAccount>lambdaQuery()
                .eq(BenefitPointsAccount::getCustomerId, customerId));
        return toPointsVO(account);
    }

    @Transactional
    public BenefitSignInResult signIn(Long customerId, String requestId) {
        validateCustomerId(customerId);
        accountWriteFence.lockAndRequireActive(customerId, CalculateWriteAction.BENEFIT_SIGN_IN);
        LocalDate today = businessDate();
        if (!isConfigValid(true)) {
            return disabledSignIn(today, "签到配置异常，请稍后再试", DISABLED_CONFIG_ERROR);
        }
        if (today.getDayOfMonth() < signStartDay() || today.getDayOfMonth() > signEndDay()) {
            return disabledSignIn(today, "本月签到已结束，下月 1 号刷新", DISABLED_MONTH_CLOSED);
        }
        BenefitSignRecord existing = findSignRecord(customerId, today);
        if (existing != null) {
            recordRedisSignedAfterCommit(customerId, today, requestId);
            BenefitPointsVO current = points(customerId);
            return new BenefitSignInResult()
                    .setNewSigned(false)
                    .setMessage("今日已签到")
                    .setBusinessDate(today.format(DATE))
                    .setYearMonth(today.format(YEAR_MONTH))
                    .setSignedToday(true)
                    .setContinuousDays(safeInt(existing.getContinuousDays()))
                    .setRewardPoints(0)
                    .setRewardRuleCode(null)
                    .setAvailablePoints(current.getAvailablePoints())
                    .setSignEnabled(true)
                    .setDisabledReason(null);
        }
        BenefitSignInResult result = doSignIn(customerId, today, requestId);
        recordRedisSignedAfterCommit(customerId, today, requestId);
        return result;
    }

    @Transactional
    public BenefitClearPointsResult clearPointsForAccountCancel(BenefitClearPointsRequest request) {
        if (request == null || request.getCustomerId() == null || request.getCustomerId() <= 0) {
            throw new IllegalArgumentException("customerId不能为空");
        }
        String requestId = safeCancelRequestId(request.getCancelRequestId());
        String bizId = "account_cancel:" + request.getCustomerId() + ":" + requestId;
        return clearPoints(request.getCustomerId(), bizId, requestId);
    }

    @Transactional
    public BenefitClearPointsResult clearPointsForLifecycle(
            Long customerId, String operationNo, String stepCode) {
        validateCustomerId(customerId);
        if (operationNo == null || operationNo.isBlank()
                || stepCode == null || stepCode.isBlank()) {
            throw new IllegalArgumentException("operationNo和stepCode不能为空");
        }
        String normalizedOperationNo = operationNo.trim();
        String bizId = normalizedOperationNo + ":" + stepCode.trim();
        if (bizId.length() > 160) {
            throw new IllegalArgumentException("积分生命周期业务幂等ID过长");
        }
        return clearPoints(customerId, bizId, normalizedOperationNo);
    }

    private BenefitClearPointsResult clearPoints(
            Long customerId, String bizId, String requestId) {
        BenefitPointsAccount account = accountMapper.selectByCustomerIdForUpdate(customerId);
        if (account == null) {
            LocalDateTime now = LocalDateTime.now();
            BenefitPointsAccount cancelled = new BenefitPointsAccount()
                    .setCustomerId(customerId)
                    .setAvailablePoints(0)
                    .setTotalEarnedPoints(0)
                    .setTotalUsedPoints(0)
                    .setTotalClearedPoints(0)
                    .setStatus(ACCOUNT_CANCELLED)
                    .setVersion(0)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            accountMapper.insert(cancelled);
            return new BenefitClearPointsResult(0, 0, 0, ACCOUNT_CANCELLED, null);
        }
        if (ACCOUNT_CANCELLED.equals(account.getStatus())) {
            return new BenefitClearPointsResult(safeInt(account.getAvailablePoints()), 0,
                    safeInt(account.getAvailablePoints()), ACCOUNT_CANCELLED,
                    account.getLastPointsFlowId());
        }
        int before = safeInt(account.getAvailablePoints());
        LocalDateTime now = LocalDateTime.now();
        Long flowId = null;
        if (before > 0) {
            BenefitPointsFlow flow = new BenefitPointsFlow()
                    .setCustomerId(customerId)
                    .setAccountId(account.getId())
                    .setBizType(BIZ_CANCEL_CLEAR)
                    .setBizId(bizId)
                    .setPointsDelta(-before)
                    .setBalanceBefore(before)
                    .setBalanceAfter(0)
                    .setFlowDirection(FLOW_OUT)
                    .setRemark("账号注销清零积分")
                    .setRuleSnapshot("{\"reason\":\"ACCOUNT_CANCEL\"}")
                    .setRequestId(requestId)
                    .setCreatedAt(now);
            flowMapper.insert(flow);
            flowId = flow.getId();
        }
        int expectedVersion = safeInt(account.getVersion());
        account.setAvailablePoints(0)
                .setTotalClearedPoints(safeInt(account.getTotalClearedPoints()) + before)
                .setStatus(ACCOUNT_CANCELLED)
                .setLastPointsFlowId(flowId == null ? account.getLastPointsFlowId() : flowId)
                .setVersion(expectedVersion + 1)
                .setUpdatedAt(now);
        if (accountMapper.updateWithVersion(account, expectedVersion) != 1) {
            throw new IllegalStateException("积分账户清零CAS更新失败，版本冲突");
        }
        return new BenefitClearPointsResult(before, before, 0, ACCOUNT_CANCELLED, flowId);
    }

    protected BenefitSignInResult doSignIn(Long customerId, LocalDate today, String requestId) {
        String yearMonth = today.format(YEAR_MONTH);
        Map<Integer, BenefitSignRecord> byDay = recordsByDay(customerId, yearMonth);
        int continuous = countPreviousContinuous(byDay, today.getDayOfMonth()) + 1;
        RewardPlan plan = rewardPlan(continuous);
        LocalDateTime now = LocalDateTime.now();

        BenefitPointsAccount account = accountMapper.selectByCustomerIdForUpdate(customerId);
        if (account == null) {
            try {
                account = new BenefitPointsAccount()
                        .setCustomerId(customerId)
                        .setAvailablePoints(0)
                        .setTotalEarnedPoints(0)
                        .setTotalUsedPoints(0)
                        .setTotalClearedPoints(0)
                        .setStatus(ACCOUNT_ACTIVE)
                        .setVersion(0)
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
                accountMapper.insert(account);
            } catch (DuplicateKeyException ex) {
                log.info("福利积分账户并发创建命中唯一键，改为重新锁定读取 customerId={}", customerId);
            }
            account = accountMapper.selectByCustomerIdForUpdate(customerId);
        }
        if (account == null || !ACCOUNT_ACTIVE.equals(account.getStatus())) {
            throw new IllegalArgumentException("当前账号不可签到");
        }

        BenefitSignRecord record = new BenefitSignRecord()
                .setCustomerId(customerId)
                .setSignDate(today)
                .setSignYearMonth(yearMonth)
                .setDayOfMonth(today.getDayOfMonth())
                .setBitmapOffset(today.getDayOfMonth() - 1)
                .setContinuousDays(continuous)
                .setRewardPoints(plan.points())
                .setRewardRuleCode(plan.ruleCode())
                .setRewardSnapshot(ruleSnapshot(plan))
                .setSourceType("APP")
                .setRequestId(requestId)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        try {
            signRecordMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            BenefitPointsVO current = toPointsVO(account);
            return new BenefitSignInResult()
                    .setNewSigned(false)
                    .setMessage("今日已签到")
                    .setBusinessDate(today.format(DATE))
                    .setYearMonth(yearMonth)
                    .setSignedToday(true)
                    .setContinuousDays(continuous)
                    .setRewardPoints(0)
                    .setRewardRuleCode(null)
                    .setAvailablePoints(current.getAvailablePoints())
                    .setSignEnabled(true)
                    .setDisabledReason(null);
        }

        int before = safeInt(account.getAvailablePoints());
        int after = before + plan.points();
        BenefitPointsFlow flow = new BenefitPointsFlow()
                .setCustomerId(customerId)
                .setAccountId(account.getId())
                .setBizType(plan.ruleCode())
                .setBizId(String.valueOf(record.getId()))
                .setPointsDelta(plan.points())
                .setBalanceBefore(before)
                .setBalanceAfter(after)
                .setFlowDirection(FLOW_IN)
                .setSignRecordId(record.getId())
                .setRemark("福利签到积分")
                .setRuleSnapshot(ruleSnapshot(plan))
                .setRequestId(requestId)
                .setCreatedAt(now);
        flowMapper.insert(flow);

        record.setPointsFlowId(flow.getId()).setUpdatedAt(now);
        signRecordMapper.updateById(record);

        account.setAvailablePoints(after)
                .setTotalEarnedPoints(safeInt(account.getTotalEarnedPoints()) + plan.points())
                .setLastSignDate(today)
                .setLastPointsFlowId(flow.getId())
                .setVersion(safeInt(account.getVersion()) + 1)
                .setUpdatedAt(now);
        accountMapper.updateById(account);

        return new BenefitSignInResult()
                .setNewSigned(true)
                .setMessage("签到成功")
                .setBusinessDate(today.format(DATE))
                .setYearMonth(yearMonth)
                .setSignedToday(true)
                .setContinuousDays(continuous)
                .setRewardPoints(plan.points())
                .setRewardRuleCode(plan.ruleCode())
                .setAvailablePoints(after)
                .setSignEnabled(true)
                .setDisabledReason(null);
    }

    private BenefitOverviewVO baseOverview(LocalDate today) {
        return new BenefitOverviewVO()
                .setBusinessDate(today.format(DATE))
                .setYearMonth(today.format(YEAR_MONTH))
                .setDisplayDays(displayDays(today));
    }

    private List<BenefitDayVO> monthDays(LocalDate today, Map<Integer, BenefitSignRecord> records) {
        return java.util.stream.IntStream.rangeClosed(1, displayDays(today))
                .mapToObj(day -> {
                    BenefitSignRecord item = records.get(day);
                    return new BenefitDayVO()
                            .setDayOfMonth(day)
                            .setDate(today.withDayOfMonth(day).format(DATE))
                            .setSigned(item != null)
                            .setRewardPoints(item == null ? rewardPlan(day).points() : safeInt(item.getRewardPoints()))
                            .setRewardRuleCode(item == null ? rewardPlan(day).ruleCode() : item.getRewardRuleCode());
                })
                .toList();
    }

    private Map<Integer, BenefitSignRecord> recordsByDay(Long customerId, String yearMonth) {
        List<BenefitSignRecord> records = signRecordMapper.selectList(Wrappers.<BenefitSignRecord>lambdaQuery()
                .eq(BenefitSignRecord::getCustomerId, customerId)
                .eq(BenefitSignRecord::getSignYearMonth, yearMonth));
        Map<Integer, BenefitSignRecord> out = new HashMap<>();
        for (BenefitSignRecord item : records) {
            out.put(item.getDayOfMonth(), item);
        }
        return out;
    }

    private BenefitSignRecord findSignRecord(Long customerId, LocalDate date) {
        return signRecordMapper.selectOne(Wrappers.<BenefitSignRecord>lambdaQuery()
                .eq(BenefitSignRecord::getCustomerId, customerId)
                .eq(BenefitSignRecord::getSignDate, date));
    }

    private int countPreviousContinuous(Map<Integer, BenefitSignRecord> byDay, int currentDay) {
        int count = 0;
        for (int day = currentDay - 1; day >= signStartDay(); day--) {
            if (!byDay.containsKey(day)) {
                break;
            }
            count++;
        }
        return count;
    }

    private RewardPlan rewardPlan(int continuousDays) {
        BenefitSignInProperties.ContinuousReward matched = properties.getRewards().getContinuous().stream()
                .filter(item -> item.getEveryDays() != null && item.getEveryDays() > 0)
                .filter(item -> continuousDays > 0 && continuousDays % item.getEveryDays() == 0)
                .findFirst()
                .orElse(null);
        if (matched != null) {
            int defaultPoints = Boolean.TRUE.equals(matched.getIncludeDefault()) ? defaultPoints() : 0;
            return new RewardPlan(RULE_CONTINUOUS_7, defaultPoints + safeInt(matched.getPoints()));
        }
        return new RewardPlan(RULE_DAILY, defaultPoints());
    }

    private String ruleSnapshot(RewardPlan plan) {
        return "{\"ruleCode\":\"" + plan.ruleCode() + "\",\"points\":" + plan.points() + "}";
    }

    private BenefitSignInResult disabledSignIn(LocalDate today, String message, String reason) {
        return new BenefitSignInResult()
                .setNewSigned(false)
                .setMessage(message)
                .setBusinessDate(today.format(DATE))
                .setYearMonth(today.format(YEAR_MONTH))
                .setSignedToday(false)
                .setContinuousDays(0)
                .setRewardPoints(0)
                .setRewardRuleCode(null)
                .setAvailablePoints(null)
                .setSignEnabled(false)
                .setDisabledReason(reason);
    }

    private BenefitPointsVO toPointsVO(BenefitPointsAccount account) {
        return new BenefitPointsVO()
                .setAvailablePoints(account == null ? 0 : safeInt(account.getAvailablePoints()))
                .setTotalEarnedPoints(account == null ? 0 : safeInt(account.getTotalEarnedPoints()))
                .setTotalUsedPoints(account == null ? 0 : safeInt(account.getTotalUsedPoints()))
                .setTotalClearedPoints(account == null ? 0 : safeInt(account.getTotalClearedPoints()))
                .setAccountStatus(account == null ? ACCOUNT_ACTIVE : account.getStatus())
                .setRefreshedAt(LocalDateTime.now().format(DATE_TIME));
    }

    private LocalDate businessDate() {
        return LocalDate.now(ZoneId.of(timezone()));
    }

    private String timezone() {
        return properties.getTimezone() == null || properties.getTimezone().isBlank()
                ? "Asia/Shanghai"
                : properties.getTimezone().trim();
    }

    private String bitmapKey(Long customerId, LocalDate date) {
        return "benefit:sign:bitmap:" + customerId + ":" + date.format(YEAR_MONTH);
    }

    private void recordRedisSignedAfterCommit(Long customerId, LocalDate date, String requestId) {
        Runnable task = () -> recordRedisSigned(customerId, date, requestId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    private void recordRedisSigned(Long customerId, LocalDate date, String requestId) {
        try {
            String key = bitmapKey(customerId, date);
            int offset = date.getDayOfMonth() - 1;
            redisTemplate.execute(RECORD_SIGN_SCRIPT, List.of(key), String.valueOf(offset));
        } catch (RuntimeException ex) {
            log.warn("福利签到 MySQL 已入账但 Redis Bitmap 写入失败 customerId={} signDate={} requestId={}",
                    customerId, date, requestId, ex);
        }
    }

    private boolean isConfigValid(boolean logError) {
        boolean ok = Boolean.TRUE.equals(properties.getEnabled())
                && "Asia/Shanghai".equals(timezone())
                && displayDays() == 28
                && signStartDay() == 1
                && signEndDay() == 28
                && defaultPoints() > 0
                && properties.getRewards() != null
                && properties.getRewards().getContinuous() != null
                && properties.getRewards().getContinuous().stream()
                .anyMatch(item -> item.getEveryDays() != null && item.getEveryDays() == 7
                        && item.getPoints() != null && item.getPoints() == 35
                        && Boolean.FALSE.equals(item.getIncludeDefault()));
        if (!ok && logError) {
            log.error("福利签到配置缺失或非法 properties={}", properties);
        }
        return ok;
    }

    private int displayDays() {
        return properties.getDisplayDays() == null ? 28 : properties.getDisplayDays();
    }

    private int displayDays(LocalDate today) {
        return Math.max(0, Math.min(displayDays(), today.lengthOfMonth()));
    }

    private int signStartDay() {
        return properties.getSignDays() == null || properties.getSignDays().getStartDayOfMonth() == null
                ? 1
                : properties.getSignDays().getStartDayOfMonth();
    }

    private int signEndDay() {
        return properties.getSignDays() == null || properties.getSignDays().getEndDayOfMonth() == null
                ? 28
                : properties.getSignDays().getEndDayOfMonth();
    }

    private int defaultPoints() {
        return properties.getRewards() == null
                || properties.getRewards().getDefault() == null
                || properties.getRewards().getDefault().getPoints() == null
                ? 0
                : properties.getRewards().getDefault().getPoints();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeCancelRequestId(String raw) {
        return raw == null || raw.isBlank()
                ? String.valueOf(System.currentTimeMillis())
                : raw.trim();
    }

    private void validateCustomerId(Long customerId) {
        if (customerId == null || customerId <= 0) {
            throw new IllegalArgumentException("customerId不能为空");
        }
    }

    private record RewardPlan(String ruleCode, int points) {
    }
}
