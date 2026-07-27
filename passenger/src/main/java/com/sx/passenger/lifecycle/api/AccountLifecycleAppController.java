package com.sx.passenger.lifecycle.api;

import com.sx.passenger.app.AppCustomerSettingsService;
import com.sx.passenger.app.dto.AppAccountCancelSmsSendResult;
import com.sx.passenger.app.dto.AppPhoneChangeSmsSendRequest;
import com.sx.passenger.app.dto.AppSmsSendResult;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** passenger-api 专用的生命周期应用接口；浏览器不能直接访问。 */
@RestController
@RequestMapping("/api/v1/app/account-lifecycle")
public class AccountLifecycleAppController {
    private final AccountLifecycleApplicationService lifecycle;
    private final AccountLifecyclePrecheckService prechecks;
    private final AppCustomerSettingsService settings;

    public AccountLifecycleAppController(
            AccountLifecycleApplicationService lifecycle,
            AccountLifecyclePrecheckService prechecks,
            AppCustomerSettingsService settings) {
        this.lifecycle = lifecycle;
        this.prechecks = prechecks;
        this.settings = settings;
    }

    @PostMapping("/cancellations/precheck")
    public ResponseVo<AccountLifecyclePrecheckView> precheckCancellation(
            @RequestBody AccountLifecycleSmsRequest request) {
        return ResultUtil.success(prechecks.precheck(request.customerId()));
    }

    @PostMapping("/cancellations/sms/send")
    public ResponseVo<AppAccountCancelSmsSendResult> sendCancellationSms(
            @RequestBody AccountLifecycleSmsRequest request) {
        return settings.sendAccountCancelSms(request.customerId());
    }

    @PostMapping("/cancellations")
    public ResponseVo<AccountLifecycleSubmissionView> submitCancellation(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody AccountLifecycleSubmitRequest request) {
        return ResultUtil.success(lifecycle.submitCancellation(request, idempotencyKey, requestId));
    }

    @PostMapping("/phone-changes/sms/send")
    public ResponseVo<AppSmsSendResult> sendPhoneChangeSms(
            @RequestBody AccountLifecycleSmsRequest request) {
        AppPhoneChangeSmsSendRequest legacyRequest = new AppPhoneChangeSmsSendRequest();
        legacyRequest.setCustomerId(request.customerId());
        legacyRequest.setNewPhone(request.phone());
        return settings.sendPhoneChangeSms(legacyRequest);
    }

    @PostMapping("/phone-changes")
    public ResponseVo<AccountLifecycleSubmissionView> submitPhoneChange(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody AccountLifecycleSubmitRequest request) {
        return ResultUtil.success(lifecycle.submitPhoneChange(request, idempotencyKey, requestId));
    }

    @GetMapping("/operations/{operationNo}")
    public ResponseVo<AccountLifecycleOperationView> operation(
            @PathVariable String operationNo,
            @RequestParam long customerId) {
        return ResultUtil.success(lifecycle.operation(customerId, operationNo));
    }

    @PostMapping("/operations/{operationNo}/abort")
    public ResponseVo<AccountLifecycleOperationView> abort(
            @PathVariable String operationNo,
            @RequestParam long customerId) {
        return ResultUtil.success(lifecycle.abort(customerId, operationNo));
    }

    @PostMapping("/operations/{operationNo}/recheck")
    public ResponseVo<AccountLifecycleOperationView> recheck(
            @PathVariable String operationNo,
            @RequestParam long customerId) {
        return ResultUtil.success(lifecycle.recheck(customerId, operationNo));
    }
}
