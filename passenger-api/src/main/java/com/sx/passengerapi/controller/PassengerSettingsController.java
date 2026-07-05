package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.settings.AccountCancelConfirmRequest;
import com.sx.passengerapi.model.settings.AccountCancelResultVO;
import com.sx.passengerapi.model.settings.PhoneChangeConfirmRequest;
import com.sx.passengerapi.model.settings.PhoneChangeResultVO;
import com.sx.passengerapi.model.settings.PhoneChangeSmsSendRequest;
import com.sx.passengerapi.model.settings.SettingsProfileVO;
import com.sx.passengerapi.model.settings.SettingsSmsSendResultVO;
import com.sx.passengerapi.service.PassengerSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端设置（对外），经 gateway 访问。
 * 统一前缀：{@code /app/api/v1/settings}
 */
@RestController
@RequestMapping("/app/api/v1/settings")
public class PassengerSettingsController {
    private static final String USER_ID_HEADER = "X-User-Id";

    private final PassengerSettingsService passengerSettingsService;

    public PassengerSettingsController(PassengerSettingsService passengerSettingsService) {
        this.passengerSettingsService = passengerSettingsService;
    }

    /**
     * 设置首页账号摘要。
     * {@code GET /app/api/v1/settings/profile}
     */
    @GetMapping("/profile")
    public ResponseVo<SettingsProfileVO> profile(@RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerSettingsService.profile(requireCustomerId(customerId)));
    }

    /**
     * 发送更换手机号验证码。
     * {@code POST /app/api/v1/settings/phone-change/sms/send}
     */
    @PostMapping("/phone-change/sms/send")
    public ResponseVo<SettingsSmsSendResultVO> sendPhoneChangeSms(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
            @Valid @RequestBody PhoneChangeSmsSendRequest body) {
        return ResultUtil.success(passengerSettingsService.sendPhoneChangeSms(requireCustomerId(customerId), body));
    }

    /**
     * 确认更换手机号；成功后 BFF 会让当前 token 失效，前端需重新登录。
     * {@code POST /app/api/v1/settings/phone-change/confirm}
     */
    @PostMapping("/phone-change/confirm")
    public ResponseVo<PhoneChangeResultVO> confirmPhoneChange(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
            @Valid @RequestBody PhoneChangeConfirmRequest body) {
        return ResultUtil.success(passengerSettingsService.confirmPhoneChange(requireCustomerId(customerId), body));
    }

    /**
     * 发送注销验证码到当前绑定手机号。
     * {@code POST /app/api/v1/settings/account-cancel/sms/send}
     */
    @PostMapping("/account-cancel/sms/send")
    public ResponseVo<SettingsSmsSendResultVO> sendAccountCancelSms(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long customerId) {
        return ResultUtil.success(passengerSettingsService.sendAccountCancelSms(requireCustomerId(customerId)));
    }

    /**
     * 确认注销；成功后 BFF 会让当前 token 失效，前端立刻退回登录态。
     * {@code POST /app/api/v1/settings/account-cancel/confirm}
     */
    @PostMapping("/account-cancel/confirm")
    public ResponseVo<AccountCancelResultVO> confirmAccountCancel(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long customerId,
            @Valid @RequestBody AccountCancelConfirmRequest body) {
        return ResultUtil.success(passengerSettingsService.confirmAccountCancel(requireCustomerId(customerId), body));
    }

    private static long requireCustomerId(Long customerId) {
        if (customerId == null || customerId <= 0) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        return customerId;
    }
}
