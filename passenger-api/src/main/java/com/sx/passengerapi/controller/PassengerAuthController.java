package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.service.PassengerAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端认证（对外），经 gateway 访问。
 * 统一前缀：{@code /app/api/v1/auth}
 */
@RestController
@RequestMapping("/app/api/v1/auth")
public class PassengerAuthController {

    private final PassengerAuthService passengerAuthService;

    public PassengerAuthController(PassengerAuthService passengerAuthService) {
        this.passengerAuthService = passengerAuthService;
    }

    /**
     * 发送短信验证码。
     * {@code POST /app/api/v1/auth/sms/send}
     */
    @PostMapping("/sms/send")
    public ResponseVo<Void> sendSms(@Valid @RequestBody com.sx.passengerapi.model.auth.SmsSendRequest body) {
        passengerAuthService.sendSms(body.getPhone());
        return ResultUtil.success(null);
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
}

