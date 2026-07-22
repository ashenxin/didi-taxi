package com.sx.passengerapi.service;

import com.sx.passengerapi.client.PassengerCoreSettingsClient;
import com.sx.passengerapi.client.dto.AppAccountCancelConfirmRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeConfirmRequest;
import com.sx.passengerapi.client.dto.AppPhoneChangeResult;
import com.sx.passengerapi.client.dto.PassengerLifecycleResult;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * BFF 账号生命周期唯一确认编排边界：核心事务成功后，同步推进本节点 WS generation 栅栏并关闭旧会话。
 */
@Component
public class PassengerLifecycleOrchestrator {

    private static final String PHONE_CHANGED = "phone_changed";
    private static final String ACCOUNT_CANCELLED = "account_cancelled";

    private final PassengerCoreSettingsClient core;
    private final PassengerWsSessionRegistry sessions;

    public PassengerLifecycleOrchestrator(
            PassengerCoreSettingsClient core,
            PassengerWsSessionRegistry sessions) {
        this.core = core;
        this.sessions = sessions;
    }

    public AppPhoneChangeResult confirmPhoneChange(AppPhoneChangeConfirmRequest request) {
        return complete(core.confirmPhoneChange(request), request.getCustomerId(), PHONE_CHANGED,
                result -> Boolean.TRUE.equals(result.getChanged()));
    }

    public AppAccountCancelResult confirmAccountCancel(AppAccountCancelConfirmRequest request) {
        return complete(core.confirmAccountCancel(request), request.getCustomerId(), ACCOUNT_CANCELLED,
                result -> Boolean.TRUE.equals(result.getCancelled()));
    }

    private <T extends PassengerLifecycleResult> T complete(
            ResponseVo<T> response,
            Long expectedCustomerId,
            String expectedReason,
            Predicate<T> operationCompleted) {
        T result = unwrap(response);
        if (result == null
                || !operationCompleted.test(result)
                || expectedCustomerId == null
                || !Objects.equals(expectedCustomerId, result.getCustomerId())
                || result.getNewAuthEpoch() == null
                || result.getNewAuthEpoch() < 1
                || !Boolean.TRUE.equals(result.getRequireLogin())
                || !expectedReason.equals(result.getRevocationReason())) {
            throw new BizErrorException(502, "账号生命周期服务返回不完整");
        }
        sessions.closeCustomerSessions(result.getCustomerId(), result.getRevocationReason());
        return result;
    }

    private static <T> T unwrap(ResponseVo<T> response) {
        if (response == null || response.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (response.getCode() != 200) {
            throw new BizErrorException(response.getCode(), response.getMsg());
        }
        return response.getData();
    }
}
