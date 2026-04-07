package com.sx.passengerapi.client;

import com.sx.passengerapi.client.dto.AppAuthCustomerBrief;
import com.sx.passengerapi.client.dto.AppLoginPasswordRequest;
import com.sx.passengerapi.client.dto.AppSmsLoginRequest;
import com.sx.passengerapi.client.dto.AppSmsSendRequest;
import com.sx.passengerapi.common.vo.ResponseVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调用 passenger 核心服务的乘客认证接口。
 */
@FeignClient(name = "passengerCoreAuth", url = "${services.passenger.base-url:http://127.0.0.1:8092}")
public interface PassengerCoreAuthClient {

    @PostMapping("/api/v1/app/auth/login-password")
    ResponseVo<AppAuthCustomerBrief> loginPassword(@RequestBody AppLoginPasswordRequest body);

    @PostMapping("/api/v1/app/auth/sms/send")
    ResponseVo<Void> sendSms(@RequestBody AppSmsSendRequest body);

    @PostMapping("/api/v1/app/auth/login-sms")
    ResponseVo<AppAuthCustomerBrief> loginSms(@RequestBody AppSmsLoginRequest body);
}
