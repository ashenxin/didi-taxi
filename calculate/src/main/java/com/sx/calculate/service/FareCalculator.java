package com.sx.calculate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.model.dto.FareRuleSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class FareCalculator {

    public static final String VERSION = "fare-v1";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ObjectMapper objectMapper;
    private final BigDecimal maxFinalAmount;

    public FareCalculator(ObjectMapper objectMapper,
                          @Value("${calculate.fare.max-final-amount:10000.00}") BigDecimal maxFinalAmount) {
        this.objectMapper = objectMapper;
        this.maxFinalAmount = maxFinalAmount.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculate(String snapshotJson, String version,
                                long distanceMeters, long durationSeconds) {
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("不支持的计价版本: " + version);
        }
        if (distanceMeters < 0 || durationSeconds < 0) {
            throw new IllegalArgumentException("计价指标不合法");
        }
        FareRuleSnapshot snapshot = readSnapshot(snapshotJson);
        validateSnapshot(snapshot);
        return calculate(snapshot, distanceMeters, durationSeconds);
    }

    public BigDecimal calculate(FareRuleSnapshot rule, long distanceMeters, long durationSeconds) {
        validateSnapshot(rule);
        if (distanceMeters < 0 || durationSeconds < 0) {
            throw new IllegalArgumentException("计价指标不合法");
        }
        BigDecimal distanceKm = BigDecimal.valueOf(distanceMeters)
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        BigDecimal durationMin = BigDecimal.valueOf(durationSeconds)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal excessKm = distanceKm.subtract(rule.getIncludedDistanceKm()).max(ZERO);
        BigDecimal excessMin = durationMin
                .subtract(BigDecimal.valueOf(rule.getIncludedDurationMin())).max(ZERO);
        BigDecimal amount = rule.getBaseFare()
                .add(excessKm.multiply(rule.getPerKmPrice()))
                .add(excessMin.multiply(rule.getPerMinutePrice()));
        if (rule.getMinimumFare() != null && amount.compareTo(rule.getMinimumFare()) < 0) {
            amount = rule.getMinimumFare();
        }
        if (rule.getMaximumFare() != null && amount.compareTo(rule.getMaximumFare()) > 0) {
            amount = rule.getMaximumFare();
        }
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(ZERO) <= 0 || rounded.compareTo(maxFinalAmount) > 0) {
            throw new IllegalArgumentException("AMOUNT_OUT_OF_RANGE: 最终车费必须在0.01至" + maxFinalAmount + "之间");
        }
        return rounded;
    }

    private FareRuleSnapshot readSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("计价规则快照不完整");
        }
        try {
            return objectMapper.readValue(snapshotJson, FareRuleSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("计价规则快照格式不合法", e);
        }
    }

    private static void validateSnapshot(FareRuleSnapshot rule) {
        if (rule == null || rule.getBaseFare() == null || rule.getIncludedDistanceKm() == null
                || rule.getIncludedDurationMin() == null || rule.getPerKmPrice() == null
                || rule.getPerMinutePrice() == null) {
            throw new IllegalArgumentException("计价规则快照不完整");
        }
        if (rule.getBaseFare().compareTo(ZERO) < 0 || rule.getIncludedDistanceKm().compareTo(ZERO) < 0
                || rule.getIncludedDurationMin() < 0 || rule.getPerKmPrice().compareTo(ZERO) < 0
                || rule.getPerMinutePrice().compareTo(ZERO) < 0
                || (rule.getMinimumFare() != null && rule.getMinimumFare().compareTo(ZERO) < 0)
                || (rule.getMaximumFare() != null && rule.getMaximumFare().compareTo(ZERO) <= 0)) {
            throw new IllegalArgumentException("计价规则快照不完整: 金额或包含量不合法");
        }
    }
}
