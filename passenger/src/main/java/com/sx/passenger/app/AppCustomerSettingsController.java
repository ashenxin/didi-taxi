package com.sx.passenger.app;

import com.sx.passenger.app.dto.AppAccountCancelConfirmRequest;
import com.sx.passenger.app.dto.AppAccountCancelResult;
import com.sx.passenger.app.dto.AppAccountCancelSmsSendResult;
import com.sx.passenger.app.dto.AppPhoneChangeConfirmRequest;
import com.sx.passenger.app.dto.AppPhoneChangeResult;
import com.sx.passenger.app.dto.AppPhoneChangeSmsSendRequest;
import com.sx.passenger.app.dto.AppSettingsCustomerIdRequest;
import com.sx.passenger.app.dto.AppSettingsProfileResponse;
import com.sx.passenger.app.dto.AppSmsSendResult;
import com.sx.passenger.common.vo.ResponseVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端设置（供 passenger-api Feign 调用）。
 * 前缀 {@code /api/v1/app/settings}，由 BFF 传入已认证的 customerId。
 */
@RestController
@RequestMapping("/api/v1/app/settings")
public class AppCustomerSettingsController {
    private final AppCustomerSettingsService appCustomerSettingsService;

    public AppCustomerSettingsController(AppCustomerSettingsService appCustomerSettingsService) {
        this.appCustomerSettingsService = appCustomerSettingsService;
    }

    /**
     * 设置首页所需的账号摘要。
     * {@code POST /api/v1/app/settings/profile}
     */
    @PostMapping("/profile")
    public ResponseVo<AppSettingsProfileResponse> profile(@Valid @RequestBody AppSettingsCustomerIdRequest body) {
        return appCustomerSettingsService.profile(body.getCustomerId());
    }

    /**
     * 发送更换手机号验证码；验证码发到新手机号。
     * {@code POST /api/v1/app/settings/phone-change/sms/send}
     */
    @PostMapping("/phone-change/sms/send")
    public ResponseVo<AppSmsSendResult> sendPhoneChangeSms(@Valid @RequestBody AppPhoneChangeSmsSendRequest body) {
        return appCustomerSettingsService.sendPhoneChangeSms(body);
    }

    /**
     * 确认更换手机号；只更新当前 customer 行，不做账号合并。
     * {@code POST /api/v1/app/settings/phone-change/confirm}
     */
    @PostMapping("/phone-change/confirm")
    public ResponseVo<AppPhoneChangeResult> confirmPhoneChange(@Valid @RequestBody AppPhoneChangeConfirmRequest body) {
        return appCustomerSettingsService.confirmPhoneChange(body);
    }

    /**
     * 发送注销验证码；验证码发到当前绑定手机号。
     * {@code POST /api/v1/app/settings/account-cancel/sms/send}
     */
    @PostMapping("/account-cancel/sms/send")
    public ResponseVo<AppAccountCancelSmsSendResult> sendAccountCancelSms(@Valid @RequestBody AppSettingsCustomerIdRequest body) {
        return appCustomerSettingsService.sendAccountCancelSms(body.getCustomerId());
    }

    /**
     * 确认注销；核心服务只负责逻辑删除，进行中订单校验由 BFF 先完成。
     * {@code POST /api/v1/app/settings/account-cancel/confirm}
     */
    @PostMapping("/account-cancel/confirm")
    public ResponseVo<AppAccountCancelResult> confirmAccountCancel(@Valid @RequestBody AppAccountCancelConfirmRequest body) {
        return appCustomerSettingsService.confirmAccountCancel(body);
    }
}
