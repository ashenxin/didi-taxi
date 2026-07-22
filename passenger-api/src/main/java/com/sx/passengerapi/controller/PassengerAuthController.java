package com.sx.passengerapi.controller;

import com.sx.passengerapi.auth.PassengerAuthContext;
import com.sx.passengerapi.auth.PassengerSessionScope;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.model.auth.PassengerLogoutResult;
import com.sx.passengerapi.model.auth.SmsSendResult;
import com.sx.passengerapi.service.PassengerAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端认证（对外），经 gateway 访问。
 * 统一前缀：{@code /app/api/v1/auth}
 */
@RestController
@RequestMapping("/app/api/v1/auth")
public class PassengerAuthController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_PHONE_HEADER = "X-User-Phone";
    private static final String AUTH_EPOCH_HEADER = "X-Auth-Epoch";
    private static final String AUTH_SCOPE_HEADER = "X-Auth-Scope";
    private static final String OPERATION_NO_HEADER = "X-Lifecycle-Operation-No";

    private final PassengerAuthService passengerAuthService;

    public PassengerAuthController(PassengerAuthService passengerAuthService) {
        this.passengerAuthService = passengerAuthService;
    }

    /**
     * 发送短信验证码。
     * {@code POST /app/api/v1/auth/sms/send}
     */
    @PostMapping("/sms/send")
    public ResponseVo<SmsSendResult> sendSms(@Valid @RequestBody com.sx.passengerapi.model.auth.SmsSendRequest body) {
        return ResultUtil.success(passengerAuthService.sendSms(body.getPhone()));
    }

    /**
     * 短信验证码登录。
     * {@code POST /app/api/v1/auth/login-sms}
     */
    @PostMapping("/login-sms")
    public ResponseVo<CustomerLoginResponse> loginSms(@Valid @RequestBody com.sx.passengerapi.model.auth.SmsLoginRequest body) {
        return ResultUtil.success(passengerAuthService.loginSms(body.getPhone(), body.getCode()));
    }

    /**
     * 手机号密码登录。
     * {@code POST /app/api/v1/auth/login-password}
     */
    @PostMapping("/login-password")
    public ResponseVo<CustomerLoginResponse> loginPassword(@Valid @RequestBody com.sx.passengerapi.model.auth.PasswordLoginRequest body) {
        return ResultUtil.success(passengerAuthService.loginPassword(body.getPhone(), body.getPassword()));
    }

    /**
     * 退出登录（须 Bearer）。
     * {@code POST /app/api/v1/auth/logout}
     */
    @PostMapping("/logout")
    public ResponseVo<PassengerLogoutResult> logout(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestHeader(value = AUTH_EPOCH_HEADER, required = false) Long authEpoch) {
        if (passengerId == null || authEpoch == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        return ResultUtil.success(passengerAuthService.logout(passengerId, authEpoch));
    }

    /**
     * 用 API token（audit=1）换取 WebSocket 握手 token（audit=2）。
     * {@code POST /app/api/v1/auth/ws-token}
     */
    @PostMapping("/ws-token")
    public ResponseVo<CustomerLoginResponse> wsToken(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestHeader(value = USER_PHONE_HEADER, required = false) String phone,
            @RequestHeader(value = AUTH_EPOCH_HEADER, required = false) Long authEpoch,
            @RequestHeader(value = AUTH_SCOPE_HEADER, required = false) String scope,
            @RequestHeader(value = OPERATION_NO_HEADER, required = false) String operationNo) {
        if (passengerId == null || authEpoch == null || scope == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        final PassengerSessionScope trustedScope;
        try {
            trustedScope = PassengerSessionScope.valueOf(scope);
        } catch (IllegalArgumentException ex) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        PassengerAuthContext context = new PassengerAuthContext(
                passengerId, phone, authEpoch, trustedScope, 1, operationNo);
        return ResultUtil.success(passengerAuthService.issueWsToken(context));
    }
}
