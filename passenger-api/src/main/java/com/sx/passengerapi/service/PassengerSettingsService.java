package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.PassengerCoreSettingsClient;
import com.sx.passengerapi.client.dto.AppAccountCancelSmsSendResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeConfirmRequest;
import com.sx.passengerapi.client.dto.AppPhoneChangeResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeSmsSendRequest;
import com.sx.passengerapi.client.dto.AppSettingsCustomerIdRequest;
import com.sx.passengerapi.client.dto.AppSettingsProfileResponse;
import com.sx.passengerapi.client.dto.AppSmsSendResult;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.lifecycle.LifecycleRolloutMetrics;
import com.sx.passengerapi.lifecycle.LifecycleRolloutProperties;
import com.sx.passengerapi.lifecycle.LifecycleRolloutRouter;
import com.sx.passengerapi.model.lifecycle.AccountCancellationSubmitRequest;
import com.sx.passengerapi.model.lifecycle.AccountLifecycleSubmissionVO;
import com.sx.passengerapi.model.lifecycle.PhoneChangeSubmitRequest;
import com.sx.passengerapi.model.settings.AccountCancelConfirmRequest;
import com.sx.passengerapi.model.settings.AccountCancelResultVO;
import com.sx.passengerapi.model.settings.PhoneChangeConfirmRequest;
import com.sx.passengerapi.model.settings.PhoneChangeResultVO;
import com.sx.passengerapi.model.settings.PhoneChangeSmsSendRequest;
import com.sx.passengerapi.model.settings.SettingsProfileVO;
import com.sx.passengerapi.model.settings.SettingsSmsSendResultVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 旧 settings 路径的兼容适配层。
 *
 * <p>该类只负责 DTO 兼容和灰度路由。跨订单、钱包、券积分的旧注销编排已经隔离到
 * {@link LegacyAccountCancellationAdapter}，命中新流程时统一交给
 * {@link PassengerAccountLifecycleService}。
 */
@Service
@Slf4j
public class PassengerSettingsService {
    private final PassengerCoreSettingsClient core;
    private final PassengerLifecycleOrchestrator legacyPhoneChange;
    private final PassengerAccountLifecycleService lifecycle;
    private final LegacyAccountCancellationAdapter legacyCancellation;
    private final LifecycleRolloutRouter rollout;
    private final LifecycleRolloutMetrics metrics;

    @Autowired
    public PassengerSettingsService(
            PassengerCoreSettingsClient core,
            PassengerLifecycleOrchestrator legacyPhoneChange,
            PassengerAccountLifecycleService lifecycle,
            LegacyAccountCancellationAdapter legacyCancellation,
            LifecycleRolloutRouter rollout,
            LifecycleRolloutMetrics metrics) {
        this.core = core;
        this.legacyPhoneChange = legacyPhoneChange;
        this.lifecycle = lifecycle;
        this.legacyCancellation = legacyCancellation;
        this.rollout = rollout;
        this.metrics = metrics;
    }

    /** 保留给既有单元测试和迁移期调用方的旧构造入口，默认不命中灰度。 */
    PassengerSettingsService(
            PassengerCoreSettingsClient core,
            OrderClient order,
            CalculateClient calculate,
            PassengerLifecycleOrchestrator legacyPhoneChange,
            OrderLifecycleShadowPrecheckService shadow) {
        this.core = core;
        this.legacyPhoneChange = legacyPhoneChange;
        this.lifecycle = null;
        this.legacyCancellation =
                new LegacyAccountCancellationAdapter(order, calculate, legacyPhoneChange, shadow);
        LifecycleRolloutProperties disabled = new LifecycleRolloutProperties();
        this.rollout = new LifecycleRolloutRouter(disabled);
        this.metrics = new LifecycleRolloutMetrics(new SimpleMeterRegistry());
    }

    public SettingsProfileVO profile(long customerId) {
        AppSettingsProfileResponse data = unwrap(core.profile(new AppSettingsCustomerIdRequest(customerId)));
        SettingsProfileVO result = new SettingsProfileVO();
        result.setCustomerId(data.getCustomerId());
        result.setMaskedPhone(data.getMaskedPhone());
        result.setStatus(data.getStatus());
        result.setDeleted(data.getDeleted());
        return result;
    }

    public SettingsSmsSendResultVO sendPhoneChangeSms(
            long customerId, PhoneChangeSmsSendRequest request) {
        AppSmsSendResult data = unwrap(core.sendPhoneChangeSms(
                new AppPhoneChangeSmsSendRequest(customerId, request.getNewPhone())));
        SettingsSmsSendResultVO result = new SettingsSmsSendResultVO();
        result.setMockCode(data == null ? null : data.getMockCode());
        return result;
    }

    public PhoneChangeResultVO confirmPhoneChange(
            long customerId, PhoneChangeConfirmRequest request) {
        boolean useLifecycle = rollout.useLifecycle(customerId);
        String route = useLifecycle ? "lifecycle" : "legacy";
        try {
            PhoneChangeResultVO result = useLifecycle
                    ? lifecyclePhoneChange(customerId, request)
                    : legacyPhoneChange(customerId, request);
            metrics.record("phone_change", route, "success");
            return result;
        } catch (RuntimeException failure) {
            metrics.record("phone_change", route, "failure");
            throw failure;
        }
    }

    public SettingsSmsSendResultVO sendAccountCancelSms(long customerId) {
        AppAccountCancelSmsSendResult data = unwrap(
                core.sendAccountCancelSms(new AppSettingsCustomerIdRequest(customerId)));
        SettingsSmsSendResultVO result = new SettingsSmsSendResultVO();
        result.setMockCode(data.getMockCode());
        result.setMaskedPhone(data.getMaskedPhone());
        return result;
    }

    public AccountCancelResultVO confirmAccountCancel(
            long customerId, AccountCancelConfirmRequest request) {
        boolean useLifecycle = rollout.useLifecycle(customerId);
        String route = useLifecycle ? "lifecycle" : "legacy";
        try {
            AccountCancelResultVO result = useLifecycle
                    ? lifecycleCancellation(customerId, request)
                    : legacyCancellation.confirm(customerId, request);
            metrics.record("account_cancel", route, "success");
            log.info("乘客 settings 注销请求已处理 customerId={} route={}", customerId, route);
            return result;
        } catch (RuntimeException failure) {
            metrics.record("account_cancel", route, "failure");
            throw failure;
        }
    }

    private PhoneChangeResultVO lifecyclePhoneChange(
            long customerId, PhoneChangeConfirmRequest request) {
        requireLifecycleAvailable();
        AccountLifecycleSubmissionVO submitted = lifecycle.submitPhoneChange(
                customerId,
                new PhoneChangeSubmitRequest(null, request.getNewPhone(), request.getCode()),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        PhoneChangeResultVO result = new PhoneChangeResultVO();
        result.setChanged(submitted.completed());
        result.setRequireLogin(submitted.requireLogin());
        result.setMaskedNewPhone(submitted.maskedPhone());
        result.setOperationNo(submitted.operationNo());
        result.setStatus(submitted.status());
        return result;
    }

    private PhoneChangeResultVO legacyPhoneChange(
            long customerId, PhoneChangeConfirmRequest request) {
        AppPhoneChangeResult data = legacyPhoneChange.confirmPhoneChange(
                new AppPhoneChangeConfirmRequest(customerId, request.getNewPhone(), request.getCode()));
        PhoneChangeResultVO result = new PhoneChangeResultVO();
        result.setChanged(data.getChanged());
        result.setRequireLogin(data.getRequireLogin());
        result.setMaskedNewPhone(data.getMaskedNewPhone());
        return result;
    }

    private AccountCancelResultVO lifecycleCancellation(
            long customerId, AccountCancelConfirmRequest request) {
        requireLifecycleAvailable();
        AccountLifecycleSubmissionVO submitted = lifecycle.submitCancellation(
                customerId,
                null,
                new AccountCancellationSubmitRequest(null, request.getCode(), request.getConfirm()),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        AccountCancelResultVO result = new AccountCancelResultVO();
        // 旧字段维持“请求已被系统接受”的兼容语义；最终完成态以 status/operationNo 查询。
        result.setCancelled(true);
        result.setRequireLogin(submitted.requireLogin());
        result.setOperationNo(submitted.operationNo());
        result.setStatus(submitted.status());
        result.setAccessToken(submitted.accessToken());
        result.setScope(submitted.scope());
        return result;
    }

    private void requireLifecycleAvailable() {
        if (lifecycle == null) {
            throw new BizErrorException(503, "账号生命周期服务暂未启用");
        }
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
