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

/**
 * 乘客福利与积分接口：福利概览、积分明细、每日签到以及账号注销时的积分清理。
 * 对外接口前缀为 {@code /api/v1/benefits}，清理接口仅供内部服务调用。
 */
@RestController
public class BenefitController {
    private final BenefitService benefitService;

    public BenefitController(BenefitService benefitService) {
        this.benefitService = benefitService;
    }

    /**
     * 查询乘客福利首页概览。
     * {@code GET /api/v1/benefits/overview?customerId=}
     */
    @GetMapping("/api/v1/benefits/overview")
    public ResponseVo<BenefitOverviewVO> overview(@RequestParam Long customerId) {
        try {
            return ResultUtil.success(benefitService.overview(customerId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 查询乘客积分余额与近期积分流水。
     * {@code GET /api/v1/benefits/points?customerId=}
     */
    @GetMapping("/api/v1/benefits/points")
    public ResponseVo<BenefitPointsVO> points(@RequestParam Long customerId) {
        try {
            return ResultUtil.success(benefitService.points(customerId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 执行每日签到并发放积分；{@code X-Request-Id} 用于请求幂等。
     * {@code POST /api/v1/benefits/sign-in?customerId=}
     */
    @PostMapping("/api/v1/benefits/sign-in")
    public ResponseVo<BenefitSignInResult> signIn(@RequestParam Long customerId,
                                                  @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        try {
            return ResultUtil.success(benefitService.signIn(customerId, requestId));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 账号注销内部接口：清空乘客剩余积分并记录注销原因流水。
     * {@code POST /internal/calculate/benefits/points/clear-by-account-cancel}
     */
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
