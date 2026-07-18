package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.benefit.BenefitOverviewVO;
import com.sx.passengerapi.model.benefit.BenefitPointsVO;
import com.sx.passengerapi.model.benefit.BenefitSignInResult;
import com.sx.passengerapi.service.PassengerBenefitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端福利 BFF：提供福利概览、积分信息和每日签到。
 * 统一前缀：{@code /app/api/v1/benefits}；乘客身份取自网关注入的 {@code X-User-Id}。
 */
@RestController
@RequestMapping("/app/api/v1/benefits")
public class PassengerBenefitController {
    private static final String USER_ID_HEADER = "X-User-Id";

    private final PassengerBenefitService passengerBenefitService;

    public PassengerBenefitController(PassengerBenefitService passengerBenefitService) {
        this.passengerBenefitService = passengerBenefitService;
    }

    /**
     * 查询当前乘客福利首页概览。
     * {@code GET /app/api/v1/benefits/overview}
     */
    @GetMapping("/overview")
    public ResponseVo<BenefitOverviewVO> overview(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerBenefitService.overview(requireCustomerId(customerId)));
    }

    /**
     * 查询当前乘客积分余额与近期流水。
     * {@code GET /app/api/v1/benefits/points}
     */
    @GetMapping("/points")
    public ResponseVo<BenefitPointsVO> points(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerBenefitService.points(requireCustomerId(customerId)));
    }

    /**
     * 执行每日签到；可选 {@code X-Request-Id} 用于请求幂等。
     * {@code POST /app/api/v1/benefits/sign-in}
     */
    @PostMapping("/sign-in")
    public ResponseVo<BenefitSignInResult> signIn(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
                                                  @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ResultUtil.success(passengerBenefitService.signIn(requireCustomerId(customerId), requestId));
    }

    private static long requireCustomerId(Long customerId) {
        if (customerId == null || customerId <= 0) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        return customerId;
    }
}
