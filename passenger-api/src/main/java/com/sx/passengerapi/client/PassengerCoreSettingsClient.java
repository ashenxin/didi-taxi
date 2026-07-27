package com.sx.passengerapi.client;

import com.sx.passengerapi.client.dto.AppAccountCancelConfirmRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelResult;
import com.sx.passengerapi.client.dto.AppAccountCancelSmsSendResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeConfirmRequest;
import com.sx.passengerapi.client.dto.AppPhoneChangeResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeSmsSendRequest;
import com.sx.passengerapi.client.dto.AppSettingsCustomerIdRequest;
import com.sx.passengerapi.client.dto.AppSettingsProfileResponse;
import com.sx.passengerapi.client.dto.AppSmsSendResult;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.PassengerCoreFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * passenger-api 到 passenger 核心服务的设置接口 Feign。
 * 浏览器只访问 BFF；核心服务不直接暴露 token 解析逻辑。
 */
@FeignClient(name = "passenger-service", contextId = "passengerCoreSettings",
        configuration = PassengerCoreFeignConfiguration.class)
public interface PassengerCoreSettingsClient {

    @PostMapping("/api/v1/app/settings/profile")
    ResponseVo<AppSettingsProfileResponse> profile(@RequestBody AppSettingsCustomerIdRequest body);

    @PostMapping("/api/v1/app/settings/phone-change/sms/send")
    ResponseVo<AppSmsSendResult> sendPhoneChangeSms(@RequestBody AppPhoneChangeSmsSendRequest body);

    @PostMapping("/api/v1/app/settings/phone-change/confirm")
    ResponseVo<AppPhoneChangeResult> confirmPhoneChange(@RequestBody AppPhoneChangeConfirmRequest body);

    @PostMapping("/api/v1/app/settings/account-cancel/sms/send")
    ResponseVo<AppAccountCancelSmsSendResult> sendAccountCancelSms(@RequestBody AppSettingsCustomerIdRequest body);

    @PostMapping("/api/v1/app/settings/account-cancel/confirm")
    ResponseVo<AppAccountCancelResult> confirmAccountCancel(@RequestBody AppAccountCancelConfirmRequest body);
}
