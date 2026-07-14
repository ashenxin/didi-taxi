package com.sx.calculate.controller;

import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.model.dto.BenefitClearPointsRequest;
import com.sx.calculate.model.dto.BenefitOverviewVO;
import com.sx.calculate.model.dto.BenefitPointsVO;
import com.sx.calculate.model.dto.BenefitSignInResult;
import com.sx.calculate.service.BenefitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BenefitController {
    private final BenefitService benefitService;

    public BenefitController(BenefitService benefitService) {
        this.benefitService = benefitService;
    }

    @GetMapping("/api/v1/benefits/overview")
    public ResponseVo<BenefitOverviewVO> overview(@RequestParam Long customerId) {
        try {
            return ResultUtil.success(benefitService.overview(customerId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @GetMapping("/api/v1/benefits/points")
    public ResponseVo<BenefitPointsVO> points(@RequestParam Long customerId) {
        try {
            return ResultUtil.success(benefitService.points(customerId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/api/v1/benefits/sign-in")
    public ResponseVo<BenefitSignInResult> signIn(@RequestParam Long customerId,
                                                  @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        try {
            return ResultUtil.success(benefitService.signIn(customerId, requestId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    @PostMapping("/internal/calculate/benefits/points/clear-by-account-cancel")
    public ResponseVo<Void> clearPointsByAccountCancel(@RequestBody BenefitClearPointsRequest request) {
        try {
            benefitService.clearPointsForAccountCancel(request);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }
}
