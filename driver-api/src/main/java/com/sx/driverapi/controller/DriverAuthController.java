package com.sx.driverapi.controller;

import com.sx.driverapi.common.util.ResultUtil;
import com.sx.driverapi.common.vo.ResponseVo;
import com.sx.driverapi.model.auth.DriverLoginResponse;
import com.sx.driverapi.model.auth.PasswordLoginRequest;
import com.sx.driverapi.model.auth.PasswordRegisterRequest;
import com.sx.driverapi.model.auth.SmsLoginRequest;
import com.sx.driverapi.model.auth.SmsRegisterRequest;
import com.sx.driverapi.model.auth.SmsSendRequest;
import com.sx.driverapi.service.DriverAuthService;
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

    @PostMapping("/sms/send")
    public ResponseVo<Void> sendSms(@Valid @RequestBody SmsSendRequest body) {
        driverAuthService.sendSms(body.getPhone());
        return ResultUtil.success(null);
    }

    @PostMapping("/register-sms")
    public ResponseVo<DriverLoginResponse> registerSms(@Valid @RequestBody SmsRegisterRequest body) {
        return ResultUtil.success(driverAuthService.registerSms(body.getPhone(), body.getCode()));
    }

    @PostMapping("/register-password")
    public ResponseVo<DriverLoginResponse> registerPassword(@Valid @RequestBody PasswordRegisterRequest body) {
        return ResultUtil.success(driverAuthService.registerPassword(body.getPhone(), body.getCode(), body.getPassword()));
    }

    @PostMapping("/login-sms")
    public ResponseVo<DriverLoginResponse> loginSms(@Valid @RequestBody SmsLoginRequest body) {
        return ResultUtil.success(driverAuthService.loginSms(body.getPhone(), body.getCode()));
    }

    @PostMapping("/login-password")
    public ResponseVo<DriverLoginResponse> loginPassword(@Valid @RequestBody PasswordLoginRequest body) {
        return ResultUtil.success(driverAuthService.loginPassword(body.getPhone(), body.getPassword()));
    }
}

