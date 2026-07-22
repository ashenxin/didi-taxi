package com.sx.passengerapi.service;

import com.sx.passengerapi.client.PassengerCoreSettingsClient;
import com.sx.passengerapi.client.dto.AppAccountCancelConfirmRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeConfirmRequest;
import com.sx.passengerapi.client.dto.AppPhoneChangeResult;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerLifecycleOrchestratorTest {

    private final PassengerCoreSettingsClient core = mock(PassengerCoreSettingsClient.class);
    private final PassengerWsSessionRegistry sessions = mock(PassengerWsSessionRegistry.class);
    private final PassengerLifecycleOrchestrator orchestrator =
            new PassengerLifecycleOrchestrator(core, sessions);

    @Test
    void phoneChangeClosesLocalSessionsOnlyAfterValidatedCoreSuccess() {
        AppPhoneChangeResult result = phoneChanged();
        when(core.confirmPhoneChange(any())).thenReturn(ResponseVo.success(result));
        AppPhoneChangeConfirmRequest request = new AppPhoneChangeConfirmRequest(7L, "13900139000", "123456");

        AppPhoneChangeResult actual = orchestrator.confirmPhoneChange(request);

        assertThat(actual).isSameAs(result);
        var order = inOrder(core, sessions);
        order.verify(core).confirmPhoneChange(request);
        order.verify(sessions).closeCustomerSessions(7L, "phone_changed");
    }

    @Test
    void accountCancelClosesLocalSessionsOnlyAfterValidatedCoreSuccess() {
        AppAccountCancelResult result = accountCancelled();
        when(core.confirmAccountCancel(any())).thenReturn(ResponseVo.success(result));
        AppAccountCancelConfirmRequest request = new AppAccountCancelConfirmRequest(7L, "123456", true);

        AppAccountCancelResult actual = orchestrator.confirmAccountCancel(request);

        assertThat(actual).isSameAs(result);
        var order = inOrder(core, sessions);
        order.verify(core).confirmAccountCancel(request);
        order.verify(sessions).closeCustomerSessions(7L, "account_cancelled");
    }

    @Test
    void coreFailureNeverClosesLocalSessions() {
        when(core.confirmPhoneChange(any())).thenReturn(new ResponseVo<>(409, "conflict"));

        assertThatThrownBy(() -> orchestrator.confirmPhoneChange(
                new AppPhoneChangeConfirmRequest(7L, "13900139000", "123456")))
                .isInstanceOf(RuntimeException.class);

        verify(sessions, never()).closeCustomerSessions(any(Long.class), any());
    }

    @Test
    void coreOkWithoutCompletedOperationNeverClosesLocalSessions() {
        AppPhoneChangeResult result = phoneChanged();
        result.setChanged(false);
        when(core.confirmPhoneChange(any())).thenReturn(ResponseVo.success(result));

        assertThatThrownBy(() -> orchestrator.confirmPhoneChange(
                new AppPhoneChangeConfirmRequest(7L, "13900139000", "123456")))
                .isInstanceOf(RuntimeException.class);

        verify(sessions, never()).closeCustomerSessions(any(Long.class), any());
    }

    @Test
    void successfulRevocationAdvancesGenerationAndRejectsHandshakePermitCapturedBeforeCoreCall() {
        PassengerWsSessionRegistry realSessions = new PassengerWsSessionRegistry();
        PassengerWsSessionRegistry.RegistrationPermit stalePermit = realSessions.captureRegistration(7L);
        PassengerLifecycleOrchestrator realOrchestrator = new PassengerLifecycleOrchestrator(core, realSessions);
        when(core.confirmPhoneChange(any())).thenReturn(ResponseVo.success(phoneChanged()));

        realOrchestrator.confirmPhoneChange(new AppPhoneChangeConfirmRequest(7L, "13900139000", "123456"));

        assertThat(realSessions.register(stalePermit, mock(WebSocketSession.class))).isFalse();
    }

    private static AppPhoneChangeResult phoneChanged() {
        AppPhoneChangeResult result = new AppPhoneChangeResult();
        result.setChanged(true);
        result.setRequireLogin(true);
        result.setCustomerId(7L);
        result.setNewAuthEpoch(10L);
        result.setRevocationReason("phone_changed");
        return result;
    }

    private static AppAccountCancelResult accountCancelled() {
        AppAccountCancelResult result = new AppAccountCancelResult();
        result.setCancelled(true);
        result.setRequireLogin(true);
        result.setCustomerId(7L);
        result.setNewAuthEpoch(10L);
        result.setRevocationReason("account_cancelled");
        return result;
    }
}
