package com.sx.passengerapi.client;

import com.sx.passengerapi.client.dto.AccountLifecycleOperationData;
import com.sx.passengerapi.client.dto.AccountLifecyclePrecheckData;
import com.sx.passengerapi.client.dto.AccountLifecycleSmsRequest;
import com.sx.passengerapi.client.dto.AccountLifecycleSubmissionData;
import com.sx.passengerapi.client.dto.AccountLifecycleSubmitRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelSmsSendResult;
import com.sx.passengerapi.client.dto.AppSmsSendResult;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.PassengerCoreFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** passenger-api 到 passenger 统一生命周期应用边界的 Feign 契约。 */
@FeignClient(name = "passengerCoreLifecycle",
        url = "${services.passenger.base-url:http://127.0.0.1:8092}",
        configuration = PassengerCoreFeignConfiguration.class)
public interface PassengerCoreLifecycleClient {

    @PostMapping("/api/v1/app/account-lifecycle/cancellations/precheck")
    ResponseVo<AccountLifecyclePrecheckData> precheckCancellation(
            @RequestBody AccountLifecycleSmsRequest request);

    @PostMapping("/api/v1/app/account-lifecycle/cancellations/sms/send")
    ResponseVo<AppAccountCancelSmsSendResult> sendCancellationSms(
            @RequestBody AccountLifecycleSmsRequest request);

    @PostMapping("/api/v1/app/account-lifecycle/cancellations")
    ResponseVo<AccountLifecycleSubmissionData> submitCancellation(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestBody AccountLifecycleSubmitRequest request);

    @PostMapping("/api/v1/app/account-lifecycle/phone-changes/sms/send")
    ResponseVo<AppSmsSendResult> sendPhoneChangeSms(
            @RequestBody AccountLifecycleSmsRequest request);

    @PostMapping("/api/v1/app/account-lifecycle/phone-changes")
    ResponseVo<AccountLifecycleSubmissionData> submitPhoneChange(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestBody AccountLifecycleSubmitRequest request);

    @GetMapping("/api/v1/app/account-lifecycle/operations/{operationNo}")
    ResponseVo<AccountLifecycleOperationData> operation(
            @PathVariable("operationNo") String operationNo,
            @RequestParam("customerId") long customerId);

    @PostMapping("/api/v1/app/account-lifecycle/operations/{operationNo}/abort")
    ResponseVo<AccountLifecycleOperationData> abort(
            @PathVariable("operationNo") String operationNo,
            @RequestParam("customerId") long customerId);

    @PostMapping("/api/v1/app/account-lifecycle/operations/{operationNo}/recheck")
    ResponseVo<AccountLifecycleOperationData> recheck(
            @PathVariable("operationNo") String operationNo,
            @RequestParam("customerId") long customerId);
}
