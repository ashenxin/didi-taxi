package com.sx.calculate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.dao.FareRuleEntityMapper;
import com.sx.calculate.model.FareRule;
import com.sx.calculate.model.dto.EstimateFareBody;
import com.sx.calculate.model.dto.EstimateFareResult;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计费服务：费用预估（按 fare_rule 与里程/时长计算）。
 * 统一前缀：{@code /api/v1/calculate}；通常由 {@code passenger-api} 下单链路调用。
 */
@RestController
@RequestMapping("/api/v1/calculate")
@Slf4j
public class EstimateController {

    private static final String FARE_CALCULATION_VERSION = "fare-v1";

    private final FareRuleEntityMapper fareRuleEntityMapper;
    private final ObjectMapper objectMapper;

    public EstimateController(FareRuleEntityMapper fareRuleEntityMapper, ObjectMapper objectMapper) {
        this.fareRuleEntityMapper = fareRuleEntityMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 预估费用：按省/市/产品线匹配当前有效 {@code fare_rule}，起步价 + 超里程 + 超时长，再应用最低/封顶。
     * {@code POST /api/v1/calculate/estimate}
     * 无匹配规则时业务码 {@code 404}。
     */
    @PostMapping("/estimate")
    public ResponseVo<EstimateFareResult> estimate(@Valid @RequestBody EstimateFareBody body) {
        if (body.getDistanceMeters() == null || body.getDistanceMeters() < 0) {
            return ResultUtil.requestError("distanceMeters不合法");
        }
        if (body.getDurationSeconds() == null || body.getDurationSeconds() < 0) {
            return ResultUtil.requestError("durationSeconds不合法");
        }

        LocalDateTime now = LocalDateTime.now();
        FareRule rule = fareRuleEntityMapper.selectOne(Wrappers.<FareRule>lambdaQuery()
                .eq(FareRule::getIsDeleted, 0)
                .eq(FareRule::getCompanyId, body.getCompanyId())
                .eq(FareRule::getProvinceCode, body.getProvinceCode())
                .eq(FareRule::getCityCode, body.getCityCode())
                .eq(FareRule::getProductCode, body.getProductCode())
                .le(FareRule::getEffectiveFrom, now)
                .and(w -> w.isNull(FareRule::getEffectiveTo).or().gt(FareRule::getEffectiveTo, now))
                .orderByDesc(FareRule::getEffectiveFrom)
                .orderByDesc(FareRule::getId)
                .last("LIMIT 1"));
        if (rule == null) {
            log.warn("估价：未找到计价规则 companyId={} province={} city={} product={}",
                    body.getCompanyId(), body.getProvinceCode(), body.getCityCode(), body.getProductCode());
            return ResultUtil.error(404, "未找到可用计价规则");
        }

        BigDecimal amount = estimateAmount(rule, body.getDistanceMeters(), body.getDurationSeconds());
        EstimateFareResult resp = new EstimateFareResult();
        resp.setRuleId(rule.getId());
        resp.setEstimatedAmount(amount);
        resp.setDistanceMeters(body.getDistanceMeters());
        resp.setDurationSeconds(body.getDurationSeconds());
        resp.setFareRuleSnapshot(fareRuleSnapshot(rule));
        resp.setFareCalculationVersion(FARE_CALCULATION_VERSION);
        log.info("估价：ruleId={} amount={} distanceM={} durationS={}",
                rule.getId(), amount, body.getDistanceMeters(), body.getDurationSeconds());
        return ResultUtil.success(resp);
    }

    private String fareRuleSnapshot(FareRule rule) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ruleId", rule.getId());
        snapshot.put("baseFare", rule.getBaseFare());
        snapshot.put("includedDistanceKm", rule.getIncludedDistanceKm());
        snapshot.put("includedDurationMin", rule.getIncludedDurationMin());
        snapshot.put("perKmPrice", rule.getPerKmPrice());
        snapshot.put("perMinutePrice", rule.getPerMinutePrice());
        snapshot.put("minimumFare", rule.getMinimumFare());
        snapshot.put("maximumFare", rule.getMaximumFare());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("计价规则快照序列化失败", e);
        }
    }

    private static BigDecimal estimateAmount(FareRule rule, long distanceMeters, long durationSeconds) {
        BigDecimal distanceKm = BigDecimal.valueOf(distanceMeters)
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        BigDecimal durationMin = BigDecimal.valueOf(durationSeconds)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        BigDecimal excessKm = distanceKm.subtract(rule.getIncludedDistanceKm());
        if (excessKm.compareTo(BigDecimal.ZERO) < 0) {
            excessKm = BigDecimal.ZERO;
        }

        BigDecimal includedMin = BigDecimal.valueOf(rule.getIncludedDurationMin() == null ? 0 : rule.getIncludedDurationMin());
        BigDecimal excessMin = durationMin.subtract(includedMin);
        if (excessMin.compareTo(BigDecimal.ZERO) < 0) {
            excessMin = BigDecimal.ZERO;
        }

        BigDecimal amount = rule.getBaseFare()
                .add(excessKm.multiply(rule.getPerKmPrice()))
                .add(excessMin.multiply(rule.getPerMinutePrice()));

        if (rule.getMinimumFare() != null && amount.compareTo(rule.getMinimumFare()) < 0) {
            amount = rule.getMinimumFare();
        }
        if (rule.getMaximumFare() != null && amount.compareTo(rule.getMaximumFare()) > 0) {
            amount = rule.getMaximumFare();
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
