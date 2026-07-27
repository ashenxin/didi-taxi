package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.PassengerSessionScope;
import com.sx.passengerapi.client.PassengerCoreLifecycleClient;
import com.sx.passengerapi.client.dto.AccountLifecycleOperationData;
import com.sx.passengerapi.client.dto.AccountLifecyclePrecheckData;
import com.sx.passengerapi.client.dto.AccountLifecycleSmsRequest;
import com.sx.passengerapi.client.dto.AccountLifecycleSubmissionData;
import com.sx.passengerapi.client.dto.AccountLifecycleSubmitRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelSmsSendResult;
import com.sx.passengerapi.client.dto.AppSmsSendResult;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.lifecycle.AccountCancellationSubmitRequest;
import com.sx.passengerapi.model.lifecycle.AccountLifecycleOperationVO;
import com.sx.passengerapi.model.lifecycle.AccountLifecyclePrecheckVO;
import com.sx.passengerapi.model.lifecycle.AccountLifecycleSubmissionVO;
import com.sx.passengerapi.model.lifecycle.PhoneChangeSubmitRequest;
import com.sx.passengerapi.model.settings.SettingsSmsSendResultVO;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** passenger-api 的生命周期 DTO、会话签发和核心服务转调边界。 */
@Service
public class PassengerAccountLifecycleService {
    private final PassengerCoreLifecycleClient core;
    private final AppJwtService jwt;
    private final PassengerWsSessionRegistry sessions;

    public PassengerAccountLifecycleService(
            PassengerCoreLifecycleClient core,
            AppJwtService jwt,
            PassengerWsSessionRegistry sessions) {
        this.core = core;
        this.jwt = jwt;
        this.sessions = sessions;
    }

    public SettingsSmsSendResultVO sendCancellationSms(long customerId) {
        AppAccountCancelSmsSendResult data = unwrap(
                core.sendCancellationSms(new AccountLifecycleSmsRequest(customerId, null)));
        SettingsSmsSendResultVO result = new SettingsSmsSendResultVO();
        result.setMockCode(data.getMockCode());
        result.setMaskedPhone(data.getMaskedPhone());
        result.setLifecycleVersion(data.getLifecycleVersion());
        return result;
    }

    public AccountLifecyclePrecheckVO precheckCancellation(long customerId) {
        AccountLifecyclePrecheckData data = unwrap(
                core.precheckCancellation(new AccountLifecycleSmsRequest(customerId, null)));
        if (data == null || data.decision() == null) {
            throw new BizErrorException(502, "账号生命周期预检返回不完整");
        }
        List<AccountLifecyclePrecheckVO.BlockerVO> blockers = safe(data.blockers()).stream()
                .map(blocker -> new AccountLifecyclePrecheckVO.BlockerVO(
                        blocker.domain(), blocker.code(), blocker.resourceType(),
                        blocker.resourceNo(), blocker.action()))
                .toList();
        return new AccountLifecyclePrecheckVO(data.decision(), blockers);
    }

    public SettingsSmsSendResultVO sendPhoneChangeSms(long customerId, String newPhone) {
        AppSmsSendResult data = unwrap(
                core.sendPhoneChangeSms(new AccountLifecycleSmsRequest(customerId, newPhone)));
        SettingsSmsSendResultVO result = new SettingsSmsSendResultVO();
        result.setMockCode(data == null ? null : data.getMockCode());
        result.setLifecycleVersion(data == null ? null : data.getLifecycleVersion());
        return result;
    }

    public AccountLifecycleSubmissionVO submitCancellation(
            long customerId,
            String phone,
            AccountCancellationSubmitRequest request,
            String idempotencyKey,
            String requestId) {
        AccountLifecycleSubmissionData data = unwrap(core.submitCancellation(
                requireIdempotencyKey(idempotencyKey), requestId(requestId),
                new AccountLifecycleSubmitRequest(customerId, request.expectedLifecycleVersion(),
                        null, request.code(), request.confirm())));
        requireSubmission(data, customerId, "ACCOUNT_CANCEL");
        boolean restricted = !data.completed() && !"ABORTED".equals(data.status());
        sessions.closeCustomerSessions(customerId,
                data.completed() ? "account_cancelled" : "account_cancelling");
        String token = restricted
                ? jwt.createPassengerToken(
                        customerId, phone, data.authEpoch(), PassengerSessionScope.LIFECYCLE_RESTRICTED,
                        1, data.operationNo())
                : null;
        return new AccountLifecycleSubmissionVO(
                data.operationNo(), data.operationType(), data.status(), data.lifecycleVersion(),
                data.completed(), data.requireLogin(), data.maskedPhone(), token,
                restricted ? "Bearer" : null,
                restricted ? jwt.getExpirationSeconds(PassengerSessionScope.LIFECYCLE_RESTRICTED) : null,
                restricted ? PassengerSessionScope.LIFECYCLE_RESTRICTED.name() : null);
    }

    public AccountLifecycleSubmissionVO submitPhoneChange(
            long customerId,
            PhoneChangeSubmitRequest request,
            String idempotencyKey,
            String requestId) {
        AccountLifecycleSubmissionData data = unwrap(core.submitPhoneChange(
                requireIdempotencyKey(idempotencyKey), requestId(requestId),
                new AccountLifecycleSubmitRequest(customerId, request.expectedLifecycleVersion(),
                        request.newPhone(), request.code(), null)));
        requireSubmission(data, customerId, "PHONE_CHANGE");
        sessions.closeCustomerSessions(customerId, "phone_changed");
        return new AccountLifecycleSubmissionVO(
                data.operationNo(), data.operationType(), data.status(), data.lifecycleVersion(),
                data.completed(), data.requireLogin(), data.maskedPhone(), null, null, null, null);
    }

    public AccountLifecycleOperationVO operation(long customerId, String operationNo) {
        return toOperation(unwrap(core.operation(operationNo, customerId)));
    }

    public AccountLifecycleOperationVO abort(long customerId, String operationNo) {
        AccountLifecycleOperationVO result = toOperation(unwrap(core.abort(operationNo, customerId)));
        sessions.closeCustomerSessions(customerId, "auth_epoch_changed");
        return result;
    }

    public AccountLifecycleOperationVO recheck(long customerId, String operationNo) {
        return toOperation(unwrap(core.recheck(operationNo, customerId)));
    }

    private static AccountLifecycleOperationVO toOperation(AccountLifecycleOperationData data) {
        if (data == null || data.operationNo() == null || data.status() == null) {
            throw new BizErrorException(502, "账号生命周期服务返回不完整");
        }
        List<AccountLifecycleOperationVO.StepVO> steps = safe(data.steps()).stream()
                .map(step -> new AccountLifecycleOperationVO.StepVO(
                        step.stepCode(), step.phase(), step.status(), step.sequenceNo(),
                        step.attemptCount(), step.errorCode()))
                .toList();
        List<AccountLifecycleOperationVO.BlockerVO> blockers = safe(data.blockers()).stream()
                .map(blocker -> new AccountLifecycleOperationVO.BlockerVO(
                        blocker.domain(), blocker.code(), blocker.resourceType(), blocker.resourceNo(),
                        blocker.status(), blocker.resolutionActions()))
                .toList();
        return new AccountLifecycleOperationVO(
                data.operationNo(), data.operationType(), data.status(), data.lifecycleVersion(),
                data.irreversibleStarted(), data.activeBlockerCount(), data.requestedAt(),
                data.completedAt(), steps, blockers);
    }

    private static void requireSubmission(
            AccountLifecycleSubmissionData data, long customerId, String operationType) {
        if (data == null || data.customerId() != customerId || data.operationNo() == null
                || data.operationNo().isBlank() || !operationType.equals(data.operationType())
                || data.status() == null || data.authEpoch() <= 0 || data.lifecycleVersion() < 0) {
            throw new BizErrorException(502, "账号生命周期服务返回不完整");
        }
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new BizErrorException(400, "Idempotency-Key不能为空且不能超过128个字符");
        }
        return value.trim();
    }

    private static String requestId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private static <T> T unwrap(ResponseVo<T> response) {
        if (response == null || response.getCode() == null) {
            throw new BizErrorException(502, "账号生命周期服务暂时不可用");
        }
        if (response.getCode() != 200) {
            throw new BizErrorException(response.getCode(), response.getMsg());
        }
        return response.getData();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
