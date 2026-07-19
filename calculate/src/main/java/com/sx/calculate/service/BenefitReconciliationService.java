package com.sx.calculate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.dao.BenefitPointsAccountMapper;
import com.sx.calculate.dao.BenefitPointsFlowMapper;
import com.sx.calculate.dao.BenefitReconciliationIssueMapper;
import com.sx.calculate.dao.BenefitSignRecordMapper;
import com.sx.calculate.model.BenefitPointsAccount;
import com.sx.calculate.model.BenefitPointsFlow;
import com.sx.calculate.model.BenefitReconciliationIssue;
import com.sx.calculate.model.BenefitSignRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BenefitReconciliationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> BITMAP_ISSUE_TYPES = Set.of("BITMAP_EXTRA_BIT", "BITMAP_REPAIR_FAILED");

    private final BenefitSignRecordMapper signRecordMapper;
    private final BenefitPointsAccountMapper accountMapper;
    private final BenefitPointsFlowMapper flowMapper;
    private final BenefitReconciliationIssueMapper issueMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public BenefitReconciliationService(BenefitSignRecordMapper signRecordMapper,
                                        BenefitPointsAccountMapper accountMapper,
                                        BenefitPointsFlowMapper flowMapper,
                                        BenefitReconciliationIssueMapper issueMapper,
                                        StringRedisTemplate redisTemplate,
                                        ObjectMapper objectMapper) {
        this.signRecordMapper = signRecordMapper;
        this.accountMapper = accountMapper;
        this.flowMapper = flowMapper;
        this.issueMapper = issueMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public ReconciliationSummary reconcile(String rawParam) {
        long startedNanos = System.nanoTime();
        ReconciliationParam param = parseParam(rawParam);
        String runId = UUID.randomUUID().toString().replace("-", "");
        MutableSummary summary = new MutableSummary(runId, param.mode().name());
        long cursor = 0L;
        do {
            List<Long> customerIds = nextCustomerIds(param, cursor);
            if (customerIds.isEmpty()) {
                break;
            }
            for (Long customerId : customerIds) {
                try {
                    reconcileCustomer(customerId, bitmapMonths(param, customerId), runId, summary);
                    summary.scannedCustomerCount++;
                } catch (RuntimeException ex) {
                    summary.failedCustomerCount++;
                    log.error("福利签到对账单用户失败 runId={} customerId={}", runId, customerId, ex);
                }
            }
            cursor = customerIds.get(customerIds.size() - 1);
            if (param.mode() == Mode.CUSTOMER) {
                break;
            }
        } while (true);

        ReconciliationSummary result = summary.toSummary((System.nanoTime() - startedNanos) / 1_000_000L);
        log.info("福利签到对账完成 runId={} mode={} scannedCustomerCount={} bitmapRepairedCount={} "
                        + "issueFoundCount={} repairFailedCount={} failedCustomerCount={} durationMs={} status={}",
                result.runId(), result.mode(), result.scannedCustomerCount(), result.bitmapRepairedCount(),
                result.issueFoundCount(), result.repairFailedCount(), result.failedCustomerCount(),
                result.durationMs(), result.status());
        return result;
    }

    private void reconcileCustomer(Long customerId,
                                   List<String> bitmapMonths,
                                   String runId,
                                   MutableSummary summary) {
        List<IssueDraft> detected = new ArrayList<>();
        List<BenefitSignRecord> records = signRecordMapper.selectList(Wrappers.<BenefitSignRecord>lambdaQuery()
                .eq(BenefitSignRecord::getCustomerId, customerId)
                .orderByAsc(BenefitSignRecord::getId));
        List<BenefitPointsFlow> flows = flowMapper.selectList(Wrappers.<BenefitPointsFlow>lambdaQuery()
                .eq(BenefitPointsFlow::getCustomerId, customerId)
                .orderByAsc(BenefitPointsFlow::getId));
        BenefitPointsAccount account = accountMapper.selectOne(Wrappers.<BenefitPointsAccount>lambdaQuery()
                .eq(BenefitPointsAccount::getCustomerId, customerId));

        for (String yearMonth : bitmapMonths) {
            reconcileBitmap(customerId, yearMonth, records, detected, summary, runId);
        }
        detectMysqlIssues(customerId, records, flows, account, detected);

        Set<String> detectedKeys = new HashSet<>();
        for (IssueDraft draft : detected) {
            String issueKey = issueKey(customerId, draft);
            detectedKeys.add(issueKey);
            upsertIssue(issueKey, customerId, draft, runId);
        }
        resolveRecoveredIssues(customerId, detectedKeys, new HashSet<>(bitmapMonths), runId);
        summary.issueFoundCount += detected.size();
    }

    private void reconcileBitmap(Long customerId,
                                 String yearMonth,
                                 List<BenefitSignRecord> allRecords,
                                 List<IssueDraft> detected,
                                 MutableSummary summary,
                                 String runId) {
        Map<Integer, BenefitSignRecord> expectedByOffset = new HashMap<>();
        for (BenefitSignRecord record : allRecords) {
            if (yearMonth.equals(record.getSignYearMonth())
                    && record.getBitmapOffset() != null
                    && record.getBitmapOffset() >= 0
                    && record.getBitmapOffset() < 28) {
                expectedByOffset.put(record.getBitmapOffset(), record);
            }
        }
        String key = bitmapKey(customerId, yearMonth);
        try {
            if (isClosedMonth(yearMonth)) {
                rebuildClosedMonth(key, expectedByOffset.keySet(), runId);
                summary.bitmapRepairedCount++;
                return;
            }
            for (int offset = 0; offset < 28; offset++) {
                boolean expected = expectedByOffset.containsKey(offset);
                Boolean actualValue = redisTemplate.opsForValue().getBit(key, offset);
                boolean actual = Boolean.TRUE.equals(actualValue);
                if (expected && !actual) {
                    redisTemplate.opsForValue().setBit(key, offset, true);
                    summary.bitmapRepairedCount++;
                } else if (!expected && actual) {
                    detected.add(new IssueDraft("BITMAP_EXTRA_BIT", "LOW", null, yearMonth,
                            "BITMAP", String.valueOf(offset), Map.of("bit", 0), Map.of("bit", 1)));
                }
            }
        } catch (RuntimeException ex) {
            summary.repairFailedCount++;
            detected.add(new IssueDraft("BITMAP_REPAIR_FAILED", "MEDIUM", null, yearMonth,
                    "BITMAP", yearMonth, Map.of("state", "MYSQL_DERIVED"),
                    Map.of("error", abbreviate(ex.toString(), 500))));
            log.warn("福利签到 Bitmap 对账失败 runId={} customerId={} yearMonth={}",
                    runId, customerId, yearMonth, ex);
        }
    }

    private void rebuildClosedMonth(String key, Set<Integer> expectedOffsets, String runId) {
        String tempKey = key + ":rebuild:" + runId;
        redisTemplate.delete(tempKey);
        try {
            redisTemplate.opsForValue().setBit(tempKey, 27, false);
            if (!Boolean.TRUE.equals(redisTemplate.expire(tempKey, 1, TimeUnit.HOURS))) {
                throw new IllegalStateException("临时Bitmap设置TTL失败");
            }
            for (Integer offset : expectedOffsets) {
                redisTemplate.opsForValue().setBit(tempKey, offset, true);
            }
            redisTemplate.rename(tempKey, key);
            if (!Boolean.TRUE.equals(redisTemplate.persist(key))) {
                throw new IllegalStateException("正式Bitmap移除TTL失败");
            }
            for (int offset = 0; offset < 28; offset++) {
                boolean actual = Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(key, offset));
                if (actual != expectedOffsets.contains(offset)) {
                    throw new IllegalStateException("历史Bitmap重建复核失败 offset=" + offset);
                }
            }
        } catch (RuntimeException ex) {
            try {
                redisTemplate.delete(tempKey);
            } catch (RuntimeException cleanupEx) {
                ex.addSuppressed(cleanupEx);
            }
            throw ex;
        }
    }

    private void detectMysqlIssues(Long customerId,
                                   List<BenefitSignRecord> records,
                                   List<BenefitPointsFlow> flows,
                                   BenefitPointsAccount account,
                                   List<IssueDraft> detected) {
        Map<Long, BenefitPointsFlow> flowById = new HashMap<>();
        Map<Long, List<BenefitPointsFlow>> flowsBySignRecord = new HashMap<>();
        for (BenefitPointsFlow flow : flows) {
            flowById.put(flow.getId(), flow);
            if (flow.getSignRecordId() != null) {
                flowsBySignRecord.computeIfAbsent(flow.getSignRecordId(), ignored -> new ArrayList<>()).add(flow);
            }
        }

        for (BenefitSignRecord record : records) {
            BenefitPointsFlow linked = record.getPointsFlowId() == null ? null : flowById.get(record.getPointsFlowId());
            List<BenefitPointsFlow> related = flowsBySignRecord.getOrDefault(record.getId(), List.of());
            if (linked == null) {
                detected.add(signIssue("SIGN_FLOW_MISSING", "HIGH", record,
                        Map.of("pointsFlow", "EXISTS"),
                        Map.of("pointsFlowId", nullable(record.getPointsFlowId()))));
            }
            if (related.size() > 1) {
                detected.add(signIssue("SIGN_FLOW_DUPLICATED", "HIGH", record,
                        Map.of("flowCount", 1), Map.of("flowCount", related.size())));
            }
            if (linked != null) {
                boolean mismatch = !Objects.equals(linked.getSignRecordId(), record.getId())
                        || !Objects.equals(linked.getCustomerId(), record.getCustomerId())
                        || !Objects.equals(linked.getBizId(), String.valueOf(record.getId()));
                if (mismatch) {
                    detected.add(signIssue("SIGN_FLOW_LINK_MISMATCH", "HIGH", record,
                            Map.of("signRecordId", record.getId(), "customerId", record.getCustomerId(),
                                    "bizId", String.valueOf(record.getId())),
                            Map.of("signRecordId", nullable(linked.getSignRecordId()),
                                    "customerId", nullable(linked.getCustomerId()),
                                    "bizId", nullable(linked.getBizId()))));
                }
                if (!Objects.equals(record.getRewardPoints(), linked.getPointsDelta())) {
                    detected.add(signIssue("SIGN_REWARD_MISMATCH", "HIGH", record,
                            Map.of("pointsDelta", safeInt(record.getRewardPoints())),
                            Map.of("pointsDelta", safeInt(linked.getPointsDelta()))));
                }
                if (!Objects.equals(record.getRewardRuleCode(), linked.getBizType())) {
                    detected.add(signIssue("SIGN_RULE_MISMATCH", "MEDIUM", record,
                            Map.of("bizType", nullable(record.getRewardRuleCode())),
                            Map.of("bizType", nullable(linked.getBizType()))));
                }
            }
        }

        if (account == null) {
            if (!records.isEmpty() || !flows.isEmpty()) {
                detected.add(new IssueDraft("ACCOUNT_MISSING", "HIGH", null, null,
                        "POINTS_ACCOUNT", String.valueOf(customerId), Map.of("account", "EXISTS"),
                        Map.of("account", "MISSING")));
            }
            return;
        }

        int flowBalance = flows.stream().mapToInt(flow -> safeInt(flow.getPointsDelta())).sum();
        int earned = flows.stream().filter(flow -> safeInt(flow.getPointsDelta()) > 0)
                .mapToInt(flow -> safeInt(flow.getPointsDelta())).sum();
        int cleared = flows.stream().filter(flow -> "ACCOUNT_CANCEL_CLEAR".equals(flow.getBizType()))
                .mapToInt(flow -> Math.abs(safeInt(flow.getPointsDelta()))).sum();
        addAccountMismatch(detected, account, "ACCOUNT_BALANCE_MISMATCH", "HIGH",
                "availablePoints", flowBalance, safeInt(account.getAvailablePoints()));
        addAccountMismatch(detected, account, "ACCOUNT_EARNED_MISMATCH", "HIGH",
                "totalEarnedPoints", earned, safeInt(account.getTotalEarnedPoints()));
        addAccountMismatch(detected, account, "ACCOUNT_CLEARED_MISMATCH", "HIGH",
                "totalClearedPoints", cleared, safeInt(account.getTotalClearedPoints()));

        Long lastFlowId = flows.stream().map(BenefitPointsFlow::getId).filter(Objects::nonNull)
                .max(Long::compareTo).orElse(null);
        if (!Objects.equals(lastFlowId, account.getLastPointsFlowId())) {
            detected.add(accountIssue("ACCOUNT_LAST_FLOW_MISMATCH", "MEDIUM", account,
                    Map.of("lastPointsFlowId", nullable(lastFlowId)),
                    Map.of("lastPointsFlowId", nullable(account.getLastPointsFlowId()))));
        }
        LocalDate lastSignDate = records.stream().map(BenefitSignRecord::getSignDate).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        if (!Objects.equals(lastSignDate, account.getLastSignDate())) {
            detected.add(accountIssue("ACCOUNT_LAST_SIGN_MISMATCH", "MEDIUM", account,
                    Map.of("lastSignDate", nullable(lastSignDate)),
                    Map.of("lastSignDate", nullable(account.getLastSignDate()))));
        }
        if ("CANCELLED".equals(account.getStatus()) && safeInt(account.getAvailablePoints()) != 0) {
            detected.add(accountIssue("CANCELLED_ACCOUNT_HAS_BALANCE", "HIGH", account,
                    Map.of("availablePoints", 0), Map.of("availablePoints", safeInt(account.getAvailablePoints()))));
        }

        List<BenefitPointsFlow> ordered = flows.stream()
                .sorted(Comparator.comparing(BenefitPointsFlow::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        for (int i = 1; i < ordered.size(); i++) {
            BenefitPointsFlow previous = ordered.get(i - 1);
            BenefitPointsFlow current = ordered.get(i);
            if (!Objects.equals(previous.getBalanceAfter(), current.getBalanceBefore())) {
                detected.add(new IssueDraft("FLOW_BALANCE_CHAIN_BROKEN", "HIGH", null, null,
                        "POINTS_FLOW", String.valueOf(current.getId()),
                        Map.of("balanceBefore", safeInt(previous.getBalanceAfter())),
                        Map.of("balanceBefore", safeInt(current.getBalanceBefore()))));
            }
        }
    }

    private void addAccountMismatch(List<IssueDraft> detected,
                                    BenefitPointsAccount account,
                                    String type,
                                    String severity,
                                    String field,
                                    int expected,
                                    int actual) {
        if (expected != actual) {
            detected.add(accountIssue(type, severity, account, Map.of(field, expected), Map.of(field, actual)));
        }
    }

    private IssueDraft signIssue(String type,
                                 String severity,
                                 BenefitSignRecord record,
                                 Object expected,
                                 Object actual) {
        return new IssueDraft(type, severity, record.getSignDate(), record.getSignYearMonth(),
                "SIGN_RECORD", String.valueOf(record.getId()), expected, actual);
    }

    private IssueDraft accountIssue(String type,
                                    String severity,
                                    BenefitPointsAccount account,
                                    Object expected,
                                    Object actual) {
        return new IssueDraft(type, severity, null, null, "POINTS_ACCOUNT",
                String.valueOf(account.getId()), expected, actual);
    }

    private void upsertIssue(String issueKey, Long customerId, IssueDraft draft, String runId) {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        BenefitReconciliationIssue existing = issueMapper.selectOne(Wrappers.<BenefitReconciliationIssue>lambdaQuery()
                .eq(BenefitReconciliationIssue::getIssueKey, issueKey));
        if (existing == null) {
            BenefitReconciliationIssue issue = new BenefitReconciliationIssue()
                    .setIssueKey(issueKey)
                    .setIssueType(draft.issueType())
                    .setSeverity(draft.severity())
                    .setCustomerId(customerId)
                    .setSignDate(draft.signDate())
                    .setYearMonth(draft.yearMonth())
                    .setReferenceType(draft.referenceType())
                    .setReferenceId(draft.referenceId())
                    .setExpectedSnapshot(toJson(draft.expected()))
                    .setActualSnapshot(toJson(draft.actual()))
                    .setStatus("OPEN")
                    .setFirstDetectedAt(now)
                    .setLastDetectedAt(now)
                    .setOccurrenceCount(1)
                    .setLastRunId(runId)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            try {
                issueMapper.insert(issue);
                return;
            } catch (DuplicateKeyException ignored) {
                existing = issueMapper.selectOne(Wrappers.<BenefitReconciliationIssue>lambdaQuery()
                        .eq(BenefitReconciliationIssue::getIssueKey, issueKey));
            }
        }
        if (existing == null) {
            throw new IllegalStateException("异常问题唯一键冲突后无法读取 issueKey=" + issueKey);
        }
        existing.setSeverity(draft.severity())
                .setExpectedSnapshot(toJson(draft.expected()))
                .setActualSnapshot(toJson(draft.actual()))
                .setStatus("OPEN")
                .setResolvedAt(null)
                .setLastDetectedAt(now)
                .setOccurrenceCount(safeInt(existing.getOccurrenceCount()) + 1)
                .setLastRunId(runId)
                .setUpdatedAt(now);
        issueMapper.updateById(existing);
    }

    private void resolveRecoveredIssues(Long customerId,
                                        Set<String> detectedKeys,
                                        Set<String> scannedBitmapMonths,
                                        String runId) {
        List<BenefitReconciliationIssue> openIssues = issueMapper.selectList(
                Wrappers.<BenefitReconciliationIssue>lambdaQuery()
                        .eq(BenefitReconciliationIssue::getCustomerId, customerId)
                        .eq(BenefitReconciliationIssue::getStatus, "OPEN"));
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        for (BenefitReconciliationIssue issue : openIssues) {
            if (detectedKeys.contains(issue.getIssueKey())) {
                continue;
            }
            if (BITMAP_ISSUE_TYPES.contains(issue.getIssueType())
                    && !scannedBitmapMonths.contains(issue.getYearMonth())) {
                continue;
            }
            issue.setStatus("RESOLVED")
                    .setResolvedAt(now)
                    .setLastRunId(runId)
                    .setUpdatedAt(now);
            issueMapper.updateById(issue);
        }
    }

    private List<Long> nextCustomerIds(ReconciliationParam param, long cursor) {
        if (param.mode() == Mode.CUSTOMER) {
            return cursor == 0L ? List.of(param.customerId()) : List.of();
        }
        List<Long> first;
        List<Long> second;
        if (param.mode() == Mode.MONTH) {
            first = signRecordMapper.selectCustomerIdsByMonthAfter(param.yearMonth(), cursor, param.pageSize());
            second = List.of();
        } else if (param.mode() == Mode.DAILY) {
            LocalDateTime since = LocalDateTime.now(BUSINESS_ZONE).minusHours(param.lookbackHours());
            first = signRecordMapper.selectCustomerIdsByMonthAfter(currentYearMonth(), cursor, param.pageSize());
            second = accountMapper.selectCustomerIdsUpdatedAfter(since, cursor, param.pageSize());
        } else {
            first = signRecordMapper.selectAllCustomerIdsAfter(cursor, param.pageSize());
            second = accountMapper.selectAllCustomerIdsAfter(cursor, param.pageSize());
        }
        return java.util.stream.Stream.concat(first.stream(), second.stream())
                .filter(Objects::nonNull)
                .filter(id -> id > cursor)
                .distinct()
                .sorted()
                .limit(param.pageSize())
                .toList();
    }

    private List<String> bitmapMonths(ReconciliationParam param, Long customerId) {
        if (param.yearMonth() != null) {
            return List.of(param.yearMonth());
        }
        if (param.mode() != Mode.CUSTOMER) {
            return List.of(currentYearMonth());
        }
        List<BenefitSignRecord> records = signRecordMapper.selectList(Wrappers.<BenefitSignRecord>lambdaQuery()
                .select(BenefitSignRecord::getSignYearMonth)
                .eq(BenefitSignRecord::getCustomerId, customerId));
        LinkedHashSet<String> months = new LinkedHashSet<>();
        for (BenefitSignRecord record : records) {
            if (record.getSignYearMonth() != null) {
                months.add(record.getSignYearMonth());
            }
        }
        months.add(currentYearMonth());
        return List.copyOf(months);
    }

    private ReconciliationParam parseParam(String rawParam) {
        try {
            JsonNode root = rawParam == null || rawParam.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawParam);
            Mode mode = Mode.valueOf(text(root, "mode", "DAILY").toUpperCase());
            Long customerId = root.hasNonNull("customerId") ? root.get("customerId").longValue() : null;
            String yearMonth = root.hasNonNull("yearMonth") ? root.get("yearMonth").asText().trim() : null;
            int lookbackHours = root.hasNonNull("lookbackHours") ? root.get("lookbackHours").intValue() : 48;
            int pageSize = root.hasNonNull("pageSize") ? root.get("pageSize").intValue() : 200;
            if (mode == Mode.CUSTOMER && (customerId == null || customerId <= 0)) {
                throw new IllegalArgumentException("CUSTOMER模式customerId必须为正整数");
            }
            if (mode == Mode.MONTH && yearMonth == null) {
                throw new IllegalArgumentException("MONTH模式yearMonth不能为空");
            }
            if (yearMonth != null) {
                if (!yearMonth.matches("\\d{6}")) {
                    throw new IllegalArgumentException("yearMonth格式必须为yyyyMM");
                }
                YearMonth parsed = YearMonth.of(Integer.parseInt(yearMonth.substring(0, 4)),
                        Integer.parseInt(yearMonth.substring(4, 6)));
                if (parsed.isAfter(YearMonth.now(BUSINESS_ZONE))) {
                    throw new IllegalArgumentException("yearMonth不能晚于当前月份");
                }
            }
            if (lookbackHours < 24 || lookbackHours > 168) {
                throw new IllegalArgumentException("lookbackHours必须在24到168之间");
            }
            if (pageSize < 50 || pageSize > 1000) {
                throw new IllegalArgumentException("pageSize必须在50到1000之间");
            }
            return new ReconciliationParam(mode, customerId, yearMonth, lookbackHours, pageSize);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new IllegalArgumentException("对账任务参数必须是合法JSON", ex);
        }
    }

    private String currentYearMonth() {
        return YearMonth.now(BUSINESS_ZONE).toString().replace("-", "");
    }

    private boolean isClosedMonth(String yearMonth) {
        YearMonth target = YearMonth.of(Integer.parseInt(yearMonth.substring(0, 4)),
                Integer.parseInt(yearMonth.substring(4, 6)));
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        YearMonth current = YearMonth.from(today);
        return target.isBefore(current) || (target.equals(current) && today.getDayOfMonth() > 28);
    }

    private String bitmapKey(Long customerId, String yearMonth) {
        return "benefit:sign:bitmap:" + customerId + ":" + yearMonth;
    }

    private String issueKey(Long customerId, IssueDraft draft) {
        String canonical = String.join("|",
                String.valueOf(customerId),
                draft.issueType(),
                draft.referenceType() == null ? "" : draft.referenceType(),
                draft.referenceId() == null ? "" : draft.referenceId(),
                draft.signDate() == null ? "" : draft.signDate().toString(),
                draft.yearMonth() == null ? "" : draft.yearMonth());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK缺少SHA-256", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("对账快照序列化失败", ex);
        }
    }

    private static Object nullable(Object value) {
        return value == null ? "NULL" : value;
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static String text(JsonNode root, String field, String defaultValue) {
        return root.hasNonNull(field) ? root.get(field).asText().trim() : defaultValue;
    }

    private static String abbreviate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private enum Mode {
        DAILY, MONTH, CUSTOMER, FULL_AUDIT
    }

    private record ReconciliationParam(Mode mode,
                                       Long customerId,
                                       String yearMonth,
                                       int lookbackHours,
                                       int pageSize) {
    }

    private record IssueDraft(String issueType,
                              String severity,
                              LocalDate signDate,
                              String yearMonth,
                              String referenceType,
                              String referenceId,
                              Object expected,
                              Object actual) {
    }

    public record ReconciliationSummary(String runId,
                                        String mode,
                                        int scannedCustomerCount,
                                        int bitmapRepairedCount,
                                        int issueFoundCount,
                                        int repairFailedCount,
                                        int failedCustomerCount,
                                        long durationMs,
                                        String status) {
    }

    private static final class MutableSummary {
        private final String runId;
        private final String mode;
        private int scannedCustomerCount;
        private int bitmapRepairedCount;
        private int issueFoundCount;
        private int repairFailedCount;
        private int failedCustomerCount;

        private MutableSummary(String runId, String mode) {
            this.runId = runId;
            this.mode = mode;
        }

        private ReconciliationSummary toSummary(long durationMs) {
            String status;
            if (failedCustomerCount > 0 && scannedCustomerCount == 0) {
                status = "FAILED";
            } else if (failedCustomerCount > 0 || repairFailedCount > 0) {
                status = "PARTIAL_FAILED";
            } else {
                status = "SUCCESS";
            }
            return new ReconciliationSummary(runId, mode, scannedCustomerCount, bitmapRepairedCount,
                    issueFoundCount, repairFailedCount, failedCustomerCount, durationMs, status);
        }
    }
}
