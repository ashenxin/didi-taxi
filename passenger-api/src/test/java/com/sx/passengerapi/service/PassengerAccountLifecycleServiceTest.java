package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.PassengerSessionScope;
import com.sx.passengerapi.client.PassengerCoreLifecycleClient;
import com.sx.passengerapi.client.dto.AccountLifecycleOperationData;
import com.sx.passengerapi.client.dto.AccountLifecycleSubmissionData;
import com.sx.passengerapi.client.dto.AppAccountCancelSmsSendResult;
import com.sx.passengerapi.client.dto.AppSmsSendResult;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.lifecycle.AccountCancellationSubmitRequest;
import com.sx.passengerapi.model.lifecycle.AccountLifecycleOperationVO;
import com.sx.passengerapi.model.lifecycle.AccountLifecycleSubmissionVO;
import com.sx.passengerapi.model.settings.SettingsSmsSendResultVO;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerAccountLifecycleServiceTest {
    private final PassengerCoreLifecycleClient core = mock(PassengerCoreLifecycleClient.class);
    private final AppJwtService jwt = mock(AppJwtService.class);
    private final PassengerWsSessionRegistry sessions = mock(PassengerWsSessionRegistry.class);
    private PassengerAccountLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new PassengerAccountLifecycleService(core, jwt, sessions);
    }

    @Test
    void cancellationIssuesRestrictedSessionBoundToOperation() {
        AccountLifecycleSubmissionData submitted = new AccountLifecycleSubmissionData(
                "LC-1", "ACCOUNT_CANCEL", "FENCED", 7L, 12L, 9L,
                false, true, "138****8000");
        when(core.submitCancellation(anyString(), anyString(), any()))
                .thenReturn(ResponseVo.success(submitted));
        when(jwt.createPassengerToken(
                eq(7L), eq("13800138000"), eq(9L),
                eq(PassengerSessionScope.LIFECYCLE_RESTRICTED), eq(1), eq("LC-1")))
                .thenReturn("restricted-token");
        when(jwt.getExpirationSeconds(PassengerSessionScope.LIFECYCLE_RESTRICTED))
                .thenReturn(1800L);

        AccountLifecycleSubmissionVO result = service.submitCancellation(
                7L, "13800138000",
                new AccountCancellationSubmitRequest(11L, "123456", true),
                "idem-1", "request-1");

        assertThat(result.operationNo()).isEqualTo("LC-1");
        assertThat(result.status()).isEqualTo("FENCED");
        assertThat(result.accessToken()).isEqualTo("restricted-token");
        assertThat(result.scope()).isEqualTo("LIFECYCLE_RESTRICTED");
        verify(sessions).closeCustomerSessions(7L, "account_cancelling");
    }

    @Test
    void operationMapsOnlySanitizedProgressFields() {
        AccountLifecycleOperationData data = new AccountLifecycleOperationData(
                "LC-1", "ACCOUNT_CANCEL", "BLOCKED", 12L,
                false, 1, LocalDateTime.now(), null,
                List.of(new AccountLifecycleOperationData.StepData(
                        "ORDER_FINAL_CHECK", "PRECONDITION", "BLOCKED", 100, 1, null)),
                List.of(new AccountLifecycleOperationData.BlockerData(
                        "ORDER", "ACTIVE_ORDER", "ORDER", "O-1", "ACTIVE", "CANCEL_ORDER")));
        when(core.operation("LC-1", 7L)).thenReturn(ResponseVo.success(data));

        AccountLifecycleOperationVO result = service.operation(7L, "LC-1");

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.steps()).singleElement()
                .extracting(AccountLifecycleOperationVO.StepVO::stepCode)
                .isEqualTo("ORDER_FINAL_CHECK");
        assertThat(result.blockers()).singleElement()
                .extracting(AccountLifecycleOperationVO.BlockerVO::resourceNo)
                .isEqualTo("O-1");
    }

    @Test
    void smsResponsesExposeTheLifecycleVersionBoundToOtp() {
        AppAccountCancelSmsSendResult cancellation = new AppAccountCancelSmsSendResult();
        cancellation.setMockCode("123456");
        cancellation.setMaskedPhone("138****8000");
        cancellation.setLifecycleVersion(12L);
        when(core.sendCancellationSms(any())).thenReturn(ResponseVo.success(cancellation));

        AppSmsSendResult phoneChange = new AppSmsSendResult();
        phoneChange.setMockCode("654321");
        phoneChange.setLifecycleVersion(13L);
        when(core.sendPhoneChangeSms(any())).thenReturn(ResponseVo.success(phoneChange));

        SettingsSmsSendResultVO cancellationResult = service.sendCancellationSms(7L);
        SettingsSmsSendResultVO phoneChangeResult =
                service.sendPhoneChangeSms(7L, "13900139000");

        assertThat(cancellationResult.getLifecycleVersion()).isEqualTo(12L);
        assertThat(phoneChangeResult.getLifecycleVersion()).isEqualTo(13L);
    }
}
