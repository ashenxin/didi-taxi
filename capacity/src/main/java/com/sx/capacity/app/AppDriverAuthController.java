package com.sx.capacity.app;

import com.sx.capacity.app.dto.AppAuthDriverBrief;
import com.sx.capacity.app.dto.AppPasswordLoginRequest;
import com.sx.capacity.app.dto.AppPasswordRegisterRequest;
import com.sx.capacity.app.dto.AppSmsLoginRequest;
import com.sx.capacity.app.dto.AppSmsRegisterRequest;
import com.sx.capacity.app.dto.AppSmsSendRequest;
import com.sx.capacity.common.vo.ResponseVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 司机端认证（供 driver-api Feign 调用）。
 * 前缀 {@code /api/v1/driver/app/auth}，不经网关暴露给浏览器时由 BFF 聚合。
 */
@RestController
@RequestMapping("/api/v1/driver/app/auth")
public class AppDriverAuthController {

    private final AppDriverAuthService service;

    public AppDriverAuthController(AppDriverAuthService service) {
        this.service = service;
    }

    /**
     * 发送短信验证码（对内）。
     * {@code POST /api/v1/driver/app/auth/sms/send}
     */
    @PostMapping("/sms/send")
    public ResponseVo<Void> sendSms(@Valid @RequestBody AppSmsSendRequest body) {
        return service.sendSmsCode(body.getPhone());
    }

    /**
     * 短信注册（对内）。
     * {@code POST /api/v1/driver/app/auth/register-sms}
     */
    @PostMapping("/register-sms")
    public ResponseVo<AppAuthDriverBrief> registerSms(@Valid @RequestBody AppSmsRegisterRequest body) {
        return service.registerSms(body);
    }

    /**
     * 短信验证后设密注册（对内）。
     * {@code POST /api/v1/driver/app/auth/register-password}
     */
    @PostMapping("/register-password")
    public ResponseVo<AppAuthDriverBrief> registerPassword(@Valid @RequestBody AppPasswordRegisterRequest body) {
        return service.registerPassword(body);
    }

    /**
     * 短信登录（对内）。
     * {@code POST /api/v1/driver/app/auth/login-sms}
     */
    @PostMapping("/login-sms")
    public ResponseVo<AppAuthDriverBrief> loginSms(@Valid @RequestBody AppSmsLoginRequest body) {
        return service.loginSms(body);
    }

    /**
     * 密码登录（对内）。
     * {@code POST /api/v1/driver/app/auth/login-password}
     */
    @PostMapping("/login-password")
    public ResponseVo<AppAuthDriverBrief> loginPassword(@Valid @RequestBody AppPasswordLoginRequest body) {
        return service.loginPassword(body);
    }
}

