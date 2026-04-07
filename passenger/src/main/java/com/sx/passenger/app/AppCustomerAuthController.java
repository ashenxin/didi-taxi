package com.sx.passenger.app;

import com.sx.passenger.app.dto.AppAuthCustomerBrief;
import com.sx.passenger.app.dto.AppLoginPasswordRequest;
import com.sx.passenger.app.dto.AppSmsLoginRequest;
import com.sx.passenger.app.dto.AppSmsSendRequest;
import com.sx.passenger.common.vo.ResponseVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端认证（供 passenger-api Feign 调用）。
 * <p>前缀 {@code /api/v1/app/auth}，不经网关暴露给浏览器时由 BFF 聚合。</p>
 */
@RestController
@RequestMapping("/api/v1/app/auth")
public class AppCustomerAuthController {

    private final AppCustomerAuthService appCustomerAuthService;

    public AppCustomerAuthController(AppCustomerAuthService appCustomerAuthService) {
        this.appCustomerAuthService = appCustomerAuthService;
    }

    @PostMapping("/login-password")
    public ResponseVo<AppAuthCustomerBrief> loginPassword(@Valid @RequestBody AppLoginPasswordRequest body) {
        return appCustomerAuthService.loginPassword(body);
    }

    @PostMapping("/sms/send")
    public ResponseVo<Void> sendSms(@Valid @RequestBody AppSmsSendRequest body) {
        return appCustomerAuthService.sendSmsCode(body.getPhone());
    }

    @PostMapping("/login-sms")
    public ResponseVo<AppAuthCustomerBrief> loginSms(@Valid @RequestBody AppSmsLoginRequest body) {
        return appCustomerAuthService.loginSms(body);
    }
}
