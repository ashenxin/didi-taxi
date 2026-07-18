package com.sx.calculate.controller;

import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.model.dto.FinalFareRequest;
import com.sx.calculate.model.dto.FinalFareResult;
import com.sx.calculate.service.FareCalculator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 完单结算计价内部接口：基于冻结的计价规则快照和实际计费里程、时长计算最终车费。
 * 统一前缀：{@code /internal/calculate}。
 */
@RestController
@RequestMapping("/internal/calculate")
public class FinalFareController {

    private final FareCalculator fareCalculator;

    public FinalFareController(FareCalculator fareCalculator) {
        this.fareCalculator = fareCalculator;
    }

    /**
     * 计算订单最终车费，并回传所用计价版本及计费里程、时长快照。
     * {@code POST /internal/calculate/final-fare}
     */
    @PostMapping("/final-fare")
    public ResponseVo<FinalFareResult> calculate(@Valid @RequestBody FinalFareRequest request) {
        FinalFareResult result = new FinalFareResult();
        result.setFinalAmount(fareCalculator.calculate(
                request.getFareRuleSnapshot(),
                request.getFareCalculationVersion(),
                request.getBillingDistanceMeters(),
                request.getBillingDurationSeconds()));
        result.setFareCalculationVersion(request.getFareCalculationVersion());
        result.setBillingDistanceMeters(request.getBillingDistanceMeters());
        result.setBillingDurationSeconds(request.getBillingDurationSeconds());
        return ResultUtil.success(result);
    }
}
