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
import com.sx.calculate.model.dto.FareRuleSnapshot;
import com.sx.calculate.service.FareCalculator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费服务：费用预估（按 fare_rule 与里程/时长计算）。
 * 统一前缀：{@code /api/v1/calculate}；通常由 {@code passenger-api} 下单链路调用。
 */
@RestController
@RequestMapping("/api/v1/calculate")
@Slf4j
public class EstimateController {

    private final FareRuleEntityMapper fareRuleEntityMapper;
    private final ObjectMapper objectMapper;
    private final FareCalculator fareCalculator;

    public EstimateController(FareRuleEntityMapper fareRuleEntityMapper, ObjectMapper objectMapper,
                              FareCalculator fareCalculator) {
        this.fareRuleEntityMapper = fareRuleEntityMapper;
        this.objectMapper = objectMapper;
        this.fareCalculator = fareCalculator;
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

        FareRuleSnapshot snapshot = FareRuleSnapshot.from(rule);
        BigDecimal amount = fareCalculator.calculate(snapshot, body.getDistanceMeters(), body.getDurationSeconds());
        EstimateFareResult resp = new EstimateFareResult();
        resp.setRuleId(rule.getId());
        resp.setEstimatedAmount(amount);
        resp.setDistanceMeters(body.getDistanceMeters());
        resp.setDurationSeconds(body.getDurationSeconds());
        resp.setFareRuleSnapshot(fareRuleSnapshot(snapshot));
        resp.setFareCalculationVersion(FareCalculator.VERSION);
        log.info("估价：ruleId={} amount={} distanceM={} durationS={}",
                rule.getId(), amount, body.getDistanceMeters(), body.getDurationSeconds());
        return ResultUtil.success(resp);
    }

    private String fareRuleSnapshot(FareRuleSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("计价规则快照序列化失败", e);
        }
    }

}
