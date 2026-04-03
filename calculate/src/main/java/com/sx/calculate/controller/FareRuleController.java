package com.sx.calculate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.calculate.dao.FareRuleEntityMapper;
import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.PageVo;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.model.FareRule;
import com.sx.calculate.model.dto.FareRuleUpsertBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计价规则 CRUD（核心服务直连，管理端亦可经 {@code admin-api} 转发）。
 * <p>统一前缀：{@code /api/v1/fare-rules}。</p>
 */
@RestController
@RequestMapping("/api/v1/fare-rules")
public class FareRuleController {

    private final FareRuleEntityMapper fareRuleEntityMapper;

    public FareRuleController(FareRuleEntityMapper fareRuleEntityMapper) {
        this.fareRuleEntityMapper = fareRuleEntityMapper;
    }

    /**
     * 计价规则分页列表。
     * <p>{@code GET /api/v1/fare-rules?pageNo=&pageSize=&provinceCode=&cityCode=&productCode=&ruleName=&active=}</p>
     */
    @GetMapping
    public ResponseVo<PageVo<FareRule>> page(@RequestParam(defaultValue = "1") Integer pageNo,
                                             @RequestParam(defaultValue = "10") Integer pageSize,
                                             @RequestParam(required = false) String provinceCode,
                                             @RequestParam(required = false) String cityCode,
                                             @RequestParam(required = false) String productCode,
                                             @RequestParam(required = false) String ruleName,
                                             @RequestParam(required = false) Integer active) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
        long offset = (long) (safePageNo - 1) * safePageSize;

        var qw = Wrappers.<FareRule>lambdaQuery()
                .eq(FareRule::getIsDeleted, 0)
                .eq(provinceCode != null && !provinceCode.isBlank(), FareRule::getProvinceCode, provinceCode)
                .eq(cityCode != null && !cityCode.isBlank(), FareRule::getCityCode, cityCode)
                .eq(productCode != null && !productCode.isBlank(), FareRule::getProductCode, productCode)
                .like(ruleName != null && !ruleName.isBlank(), FareRule::getRuleName, ruleName);
        if (active != null) {
            // active=1：effective_to 为空或大于当前时间；active=0：effective_to 非空且小于等于当前时间
            LocalDateTime now = LocalDateTime.now();
            if (active == 1) {
                qw.and(w -> w.isNull(FareRule::getEffectiveTo).or().gt(FareRule::getEffectiveTo, now));
            } else if (active == 0) {
                qw.isNotNull(FareRule::getEffectiveTo).le(FareRule::getEffectiveTo, now);
            }
        }
        qw.orderByDesc(FareRule::getId);

        Long total = fareRuleEntityMapper.selectCount(qw);
        List<FareRule> rows = fareRuleEntityMapper.selectList(qw.last("LIMIT " + offset + "," + safePageSize));

        PageVo<FareRule> resp = new PageVo<>();
        resp.setList(rows);
        resp.setTotal(total == null ? 0L : total);
        resp.setPageNo(safePageNo);
        resp.setPageSize(safePageSize);
        return ResultUtil.success(resp);
    }

    /**
     * 计价规则详情。
     * <p>{@code GET /api/v1/fare-rules/{id}}</p>
     */
    @GetMapping("/{id}")
    public ResponseVo<FareRule> detail(@PathVariable Long id) {
        if (id == null) {
            return ResultUtil.requestError("id 不能为空");
        }
        FareRule row = fareRuleEntityMapper.selectOne(Wrappers.<FareRule>lambdaQuery()
                .eq(FareRule::getId, id)
                .eq(FareRule::getIsDeleted, 0));
        if (row == null) {
            return ResultUtil.error(404, "计价规则不存在");
        }
        return ResultUtil.success(row);
    }

    /**
     * 新建计价规则。
     * <p>{@code POST /api/v1/fare-rules}</p>
     */
    @PostMapping
    public ResponseVo<Long> create(@Valid @RequestBody FareRuleUpsertBody body) {
        String err = validateBiz(body);
        if (err != null) {
            return ResultUtil.requestError(err);
        }
        FareRule row = new FareRule()
                .setProvinceCode(body.getProvinceCode())
                .setCityCode(body.getCityCode())
                .setProductCode(body.getProductCode())
                .setRuleName(body.getRuleName())
                .setEffectiveFrom(body.getEffectiveFrom())
                .setEffectiveTo(body.getEffectiveTo())
                .setBaseFare(body.getBaseFare())
                .setIncludedDistanceKm(body.getIncludedDistanceKm())
                .setIncludedDurationMin(body.getIncludedDurationMin())
                .setPerKmPrice(body.getPerKmPrice())
                .setPerMinutePrice(body.getPerMinutePrice())
                .setMinimumFare(body.getMinimumFare())
                .setMaximumFare(body.getMaximumFare())
                .setIsDeleted(0);
        fareRuleEntityMapper.insert(row);
        return ResultUtil.success(row.getId());
    }

    /**
     * 更新计价规则。
     * <p>{@code PUT /api/v1/fare-rules/{id}}</p>
     */
    @PutMapping("/{id}")
    public ResponseVo<Void> update(@PathVariable Long id, @Valid @RequestBody FareRuleUpsertBody body) {
        if (id == null) {
            return ResultUtil.requestError("id 不能为空");
        }
        FareRule existing = fareRuleEntityMapper.selectOne(Wrappers.<FareRule>lambdaQuery()
                .eq(FareRule::getId, id)
                .eq(FareRule::getIsDeleted, 0));
        if (existing == null) {
            return ResultUtil.error(404, "计价规则不存在");
        }
        String err = validateBiz(body);
        if (err != null) {
            return ResultUtil.requestError(err);
        }
        existing.setProvinceCode(body.getProvinceCode());
        existing.setCityCode(body.getCityCode());
        existing.setProductCode(body.getProductCode());
        existing.setRuleName(body.getRuleName());
        existing.setEffectiveFrom(body.getEffectiveFrom());
        existing.setEffectiveTo(body.getEffectiveTo());
        existing.setBaseFare(body.getBaseFare());
        existing.setIncludedDistanceKm(body.getIncludedDistanceKm());
        existing.setIncludedDurationMin(body.getIncludedDurationMin());
        existing.setPerKmPrice(body.getPerKmPrice());
        existing.setPerMinutePrice(body.getPerMinutePrice());
        existing.setMinimumFare(body.getMinimumFare());
        existing.setMaximumFare(body.getMaximumFare());
        fareRuleEntityMapper.updateById(existing);
        return ResultUtil.success(null);
    }

    /**
     * 逻辑删除计价规则。
     * <p>{@code DELETE /api/v1/fare-rules/{id}}</p>
     */
    @DeleteMapping("/{id}")
    public ResponseVo<Void> delete(@PathVariable Long id) {
        if (id == null) {
            return ResultUtil.requestError("id 不能为空");
        }
        FareRule existing = fareRuleEntityMapper.selectOne(Wrappers.<FareRule>lambdaQuery()
                .eq(FareRule::getId, id)
                .eq(FareRule::getIsDeleted, 0));
        if (existing == null) {
            return ResultUtil.error(404, "计价规则不存在");
        }
        existing.setIsDeleted(1);
        fareRuleEntityMapper.updateById(existing);
        return ResultUtil.success(null);
    }

    private String validateBiz(FareRuleUpsertBody body) {
        if (body.getEffectiveTo() != null && body.getEffectiveFrom() != null
                && body.getEffectiveTo().isBefore(body.getEffectiveFrom())) {
            return "生效结束时间不能早于生效开始时间";
        }
        if (!positive(body.getBaseFare())
                || !nonNegative(body.getIncludedDistanceKm())
                || body.getIncludedDurationMin() == null
                || body.getIncludedDurationMin() < 0
                || !nonNegative(body.getPerKmPrice())
                || !nonNegative(body.getPerMinutePrice())) {
            return "计价字段不合法";
        }
        if (body.getMinimumFare() != null && body.getMinimumFare().compareTo(BigDecimal.ZERO) < 0) {
            return "最低消费不能小于 0";
        }
        if (body.getMaximumFare() != null && body.getMaximumFare().compareTo(BigDecimal.ZERO) < 0) {
            return "封顶价不能小于 0";
        }
        return null;
    }

    private boolean nonNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
