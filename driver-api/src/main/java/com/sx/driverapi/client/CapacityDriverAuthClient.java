package com.sx.driverapi.client;

import com.sx.driverapi.client.dto.AppAuthDriverBrief;
import com.sx.driverapi.client.dto.AppPasswordLoginRequest;
import com.sx.driverapi.client.dto.AppPasswordRegisterRequest;
import com.sx.driverapi.client.dto.AppSmsLoginRequest;
import com.sx.driverapi.client.dto.AppSmsRegisterRequest;
import com.sx.driverapi.client.dto.AppSmsSendRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "capacity-driver-auth", url = "${services.capacity.base-url:http://127.0.0.1:8090}")
public interface CapacityDriverAuthClient {

    @PostMapping("/api/v1/driver/app/auth/sms/send")
    CoreResponseVo<Void> sendSms(@RequestBody AppSmsSendRequest body);

    @PostMapping("/api/v1/driver/app/auth/register-sms")
    CoreResponseVo<AppAuthDriverBrief> registerSms(@RequestBody AppSmsRegisterRequest body);

    @PostMapping("/api/v1/driver/app/auth/register-password")
    CoreResponseVo<AppAuthDriverBrief> registerPassword(@RequestBody AppPasswordRegisterRequest body);

    @PostMapping("/api/v1/driver/app/auth/login-sms")
    CoreResponseVo<AppAuthDriverBrief> loginSms(@RequestBody AppSmsLoginRequest body);

    @PostMapping("/api/v1/driver/app/auth/login-password")
    CoreResponseVo<AppAuthDriverBrief> loginPassword(@RequestBody AppPasswordLoginRequest body);
}

