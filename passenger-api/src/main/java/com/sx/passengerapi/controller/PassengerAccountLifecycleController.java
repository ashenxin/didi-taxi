package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.lifecycle.AccountCancellationSubmitRequest;
import com.sx.passengerapi.model.lifecycle.AccountLifecycleOperationVO;
import com.sx.passengerapi.model.lifecycle.AccountLifecyclePrecheckVO;
import com.sx.passengerapi.model.lifecycle.AccountLifecycleSubmissionVO;
import com.sx.passengerapi.model.lifecycle.LifecycleSmsSendRequest;
import com.sx.passengerapi.model.lifecycle.PhoneChangeSubmitRequest;
import com.sx.passengerapi.model.settings.SettingsSmsSendResultVO;
import com.sx.passengerapi.service.PassengerAccountLifecycleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 乘客端统一账号生命周期 API。 */
@RestController
@RequestMapping("/app/api/v1/account-lifecycle")
public class PassengerAccountLifecycleController {
    private static final String USER_ID = "X-User-Id";
    private static final String USER_PHONE = "X-User-Phone";

    private final PassengerAccountLifecycleService lifecycle;

    public PassengerAccountLifecycleController(PassengerAccountLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/cancellations/precheck")
    public ResponseVo<AccountLifecyclePrecheckVO> precheckCancellation(
            @RequestHeader(value = USER_ID, required = false) Long customerId) {
        return ResultUtil.success(lifecycle.precheckCancellation(requireCustomerId(customerId)));
    }

    @PostMapping("/cancellations/sms/send")
    public ResponseVo<SettingsSmsSendResultVO> sendCancellationSms(
            @RequestHeader(value = USER_ID, required = false) Long customerId) {
        return ResultUtil.success(lifecycle.sendCancellationSms(requireCustomerId(customerId)));
    }

    @PostMapping("/cancellations")
    public ResponseEntity<ResponseVo<AccountLifecycleSubmissionVO>> submitCancellation(
            @RequestHeader(value = USER_ID, required = false) Long customerId,
            @RequestHeader(value = USER_PHONE, required = false) String phone,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody AccountCancellationSubmitRequest body) {
        AccountLifecycleSubmissionVO result = lifecycle.submitCancellation(
                requireCustomerId(customerId), phone, body, idempotencyKey, requestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ResultUtil.success(result));
    }

    @PostMapping("/phone-changes/sms/send")
    public ResponseVo<SettingsSmsSendResultVO> sendPhoneChangeSms(
            @RequestHeader(value = USER_ID, required = false) Long customerId,
            @Valid @RequestBody LifecycleSmsSendRequest body) {
        if (body.phone() == null || body.phone().isBlank()) {
            throw new BizErrorException(400, "新手机号不能为空");
        }
        return ResultUtil.success(
                lifecycle.sendPhoneChangeSms(requireCustomerId(customerId), body.phone()));
    }

    @PostMapping("/phone-changes")
    public ResponseVo<AccountLifecycleSubmissionVO> submitPhoneChange(
            @RequestHeader(value = USER_ID, required = false) Long customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody PhoneChangeSubmitRequest body) {
        return ResultUtil.success(lifecycle.submitPhoneChange(
                requireCustomerId(customerId), body, idempotencyKey, requestId));
    }

    @GetMapping("/operations/{operationNo}")
    public ResponseVo<AccountLifecycleOperationVO> operation(
            @RequestHeader(value = USER_ID, required = false) Long customerId,
            @PathVariable String operationNo) {
        return ResultUtil.success(lifecycle.operation(requireCustomerId(customerId), operationNo));
    }

    @PostMapping("/operations/{operationNo}/abort")
    public ResponseVo<AccountLifecycleOperationVO> abort(
            @RequestHeader(value = USER_ID, required = false) Long customerId,
            @PathVariable String operationNo) {
        return ResultUtil.success(lifecycle.abort(requireCustomerId(customerId), operationNo));
    }

    @PostMapping("/operations/{operationNo}/recheck")
    public ResponseVo<AccountLifecycleOperationVO> recheck(
            @RequestHeader(value = USER_ID, required = false) Long customerId,
            @PathVariable String operationNo) {
        return ResultUtil.success(lifecycle.recheck(requireCustomerId(customerId), operationNo));
    }

    private static long requireCustomerId(Long customerId) {
        if (customerId == null || customerId <= 0) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        return customerId;
    }
}
