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
 * <p>前缀 {@code /api/v1/driver/app/auth}，不经网关暴露给浏览器时由 BFF 聚合。</p>
 */
@RestController
@RequestMapping("/api/v1/driver/app/auth")
public class AppDriverAuthController {

    private final AppDriverAuthService service;

    public AppDriverAuthController(AppDriverAuthService service) {
        this.service = service;
    }

    @PostMapping("/sms/send")
    public ResponseVo<Void> sendSms(@Valid @RequestBody AppSmsSendRequest body) {
        return service.sendSmsCode(body.getPhone());
    }

    @PostMapping("/register-sms")
    public ResponseVo<AppAuthDriverBrief> registerSms(@Valid @RequestBody AppSmsRegisterRequest body) {
        return service.registerSms(body);
    }

    @PostMapping("/register-password")
    public ResponseVo<AppAuthDriverBrief> registerPassword(@Valid @RequestBody AppPasswordRegisterRequest body) {
        return service.registerPassword(body);
    }

    @PostMapping("/login-sms")
    public ResponseVo<AppAuthDriverBrief> loginSms(@Valid @RequestBody AppSmsLoginRequest body) {
        return service.loginSms(body);
    }

    @PostMapping("/login-password")
    public ResponseVo<AppAuthDriverBrief> loginPassword(@Valid @RequestBody AppPasswordLoginRequest body) {
        return service.loginPassword(body);
    }
}

