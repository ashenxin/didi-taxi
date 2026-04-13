package com.sx.driverapi.controller;

import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.common.util.ResultUtil;
import com.sx.driverapi.common.vo.ResponseVo;
import com.sx.driverapi.model.auth.DriverLoginResponse;
import com.sx.driverapi.model.auth.PasswordLoginRequest;
import com.sx.driverapi.model.auth.PasswordRegisterRequest;
import com.sx.driverapi.model.auth.SmsLoginRequest;
import com.sx.driverapi.model.auth.SmsRegisterRequest;
import com.sx.driverapi.model.auth.SmsSendRequest;
import com.sx.driverapi.auth.DriverRequestAttributes;
import com.sx.driverapi.service.DriverAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 司机端认证（对外），经 gateway 访问。
 * <p>统一前缀：{@code /driver/api/v1/auth}</p>
 */
@RestController
@RequestMapping("/driver/api/v1/auth")
public class DriverAuthController {

    private final DriverAuthService driverAuthService;

    public DriverAuthController(DriverAuthService driverAuthService) {
        this.driverAuthService = driverAuthService;
    }

    /**
     * 发送短信验证码（注册/登录前）。
     * <p>{@code POST /driver/api/v1/auth/sms/send}</p>
     */
    @PostMapping("/sms/send")
    public ResponseVo<Void> sendSms(@Valid @RequestBody SmsSendRequest body) {
        driverAuthService.sendSms(body.getPhone());
        return ResultUtil.success(null);
    }

    /**
     * 短信验证码注册并登录。
     * <p>{@code POST /driver/api/v1/auth/register-sms}</p>
     */
    @PostMapping("/register-sms")
    public ResponseVo<DriverLoginResponse> registerSms(@Valid @RequestBody SmsRegisterRequest body) {
        return ResultUtil.success(driverAuthService.registerSms(body.getPhone(), body.getCode()));
    }

    /**
     * 短信验证后设置密码完成注册并登录。
     * <p>{@code POST /driver/api/v1/auth/register-password}</p>
     */
    @PostMapping("/register-password")
    public ResponseVo<DriverLoginResponse> registerPassword(@Valid @RequestBody PasswordRegisterRequest body) {
        return ResultUtil.success(driverAuthService.registerPassword(body.getPhone(), body.getCode(), body.getPassword()));
    }

    /**
     * 短信验证码登录。
     * <p>{@code POST /driver/api/v1/auth/login-sms}</p>
     */
    @PostMapping("/login-sms")
    public ResponseVo<DriverLoginResponse> loginSms(@Valid @RequestBody SmsLoginRequest body) {
        return ResultUtil.success(driverAuthService.loginSms(body.getPhone(), body.getCode()));
    }

    /**
     * 手机号密码登录。
     * <p>{@code POST /driver/api/v1/auth/login-password}</p>
     */
    @PostMapping("/login-password")
    public ResponseVo<DriverLoginResponse> loginPassword(@Valid @RequestBody PasswordLoginRequest body) {
        return ResultUtil.success(driverAuthService.loginPassword(body.getPhone(), body.getPassword()));
    }

    /**
     * 登出：递增服务端 token 版本，使 JWT 失效。
     * <p>{@code POST /driver/api/v1/auth/logout}</p>
     */
    @PostMapping("/logout")
    public ResponseVo<Void> logout(HttpServletRequest request) {
        Long driverId = (Long) request.getAttribute(DriverRequestAttributes.DRIVER_ID);
        if (driverId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        driverAuthService.logout(driverId);
        return ResultUtil.success(null);
    }

    /**
     * 用 API token（audit=1）换取 WebSocket 握手 token（audit=2），{@code tv} 不变。
     * <p>{@code POST /driver/api/v1/auth/ws-token}</p>
     */
    @PostMapping("/ws-token")
    public ResponseVo<DriverLoginResponse> wsToken(HttpServletRequest request) {
        Long driverId = (Long) request.getAttribute(DriverRequestAttributes.DRIVER_ID);
        String phone = (String) request.getAttribute(DriverRequestAttributes.DRIVER_PHONE);
        if (driverId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        return ResultUtil.success(driverAuthService.issueWsToken(driverId, phone));
    }
}

