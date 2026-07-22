package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.PassengerAuthContext;
import com.sx.passengerapi.client.PassengerCoreAuthClient;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.AppAuthCustomerBrief;
import com.sx.passengerapi.client.dto.InternalLogoutRequest;
import com.sx.passengerapi.client.dto.InternalLogoutResponse;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.model.auth.PassengerLogoutResult;
import com.sx.passengerapi.ws.PassengerWsProperties;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PassengerAuthServiceTest {

    private final PassengerCoreAuthClient coreAuthClient = mock(PassengerCoreAuthClient.class);
    private final PassengerCoreAuthStateClient authStateClient = mock(PassengerCoreAuthStateClient.class);
    private final AppJwtService jwtService = mock(AppJwtService.class);
    private final PassengerOrderService passengerOrderService = mock(PassengerOrderService.class);
    private final PassengerWsProperties wsProperties = new PassengerWsProperties();
    private final PassengerWsSessionRegistry sessions = mock(PassengerWsSessionRegistry.class);
    private final PassengerAuthService service = new PassengerAuthService(
            coreAuthClient, authStateClient, jwtService, passengerOrderService, wsProperties, sessions);

    @Test
    void signsExactlyTheEpochAndScopeReturnedByCoreAfterClosingOldWs() {
        when(coreAuthClient.loginPassword(any())).thenReturn(ResponseVo.success(
                brief(7L, "13800138000", 11L, "NORMAL", null)));
        when(jwtService.createPassengerToken(7L, "13800138000", 11L, NORMAL, 1, null))
                .thenReturn("token-1");
        when(jwtService.getExpirationSeconds(NORMAL)).thenReturn(7200L);

        CustomerLoginResponse response = service.loginPassword("13800138000", "secret");

        assertThat(response.getAccessToken()).isEqualTo("token-1");
        assertThat(response.getScope()).isEqualTo("NORMAL");
        assertThat(response.getOperationNo()).isNull();
        InOrder order = inOrder(sessions, jwtService);
        order.verify(sessions).closeCustomerSessions(7L, "auth_epoch_changed");
        order.verify(jwtService).createPassengerToken(7L, "13800138000", 11L, NORMAL, 1, null);
    }

    @Test
    void signsRestrictedReauthenticationForThirtyMinutes() {
        when(coreAuthClient.loginSms(any())).thenReturn(ResponseVo.success(
                brief(7L, "13800138000", 12L, "LIFECYCLE_RESTRICTED", "op-1")));
        when(jwtService.createPassengerToken(
                7L, "13800138000", 12L, LIFECYCLE_RESTRICTED, 1, "op-1"))
                .thenReturn("restricted-token");
        when(jwtService.getExpirationSeconds(LIFECYCLE_RESTRICTED)).thenReturn(1800L);

        CustomerLoginResponse response = service.loginSms("13800138000", "111111");

        assertThat(response.getScope()).isEqualTo("LIFECYCLE_RESTRICTED");
        assertThat(response.getOperationNo()).isEqualTo("op-1");
        assertThat(response.getExpiresIn()).isEqualTo(1800L);
    }

    @Test
    void websocketTokenUsesOnlyVerifiedNormalContextAndSameEpoch() {
        wsProperties.setEnabled(true);
        PassengerAuthContext context = new PassengerAuthContext(7L, "13800138000", 13L, NORMAL, 1, null);
        when(jwtService.createPassengerToken(7L, "13800138000", 13L, NORMAL, 2, null))
                .thenReturn("ws-token");
        when(jwtService.getExpirationSeconds(NORMAL)).thenReturn(7200L);

        CustomerLoginResponse response = service.issueWsToken(context);

        assertThat(response.getAccessToken()).isEqualTo("ws-token");
        assertThat(response.getScope()).isEqualTo("NORMAL");
        verify(jwtService).createPassengerToken(7L, "13800138000", 13L, NORMAL, 2, null);
        verifyNoMoreInteractions(authStateClient);
    }

    @Test
    void websocketTokenRejectsRestrictedVerifiedContext() {
        wsProperties.setEnabled(true);
        PassengerAuthContext context = new PassengerAuthContext(
                7L, "", 13L, LIFECYCLE_RESTRICTED, 1, "op-1");

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> service.issueWsToken(context));

        assertThat(error.getErrorCode()).isEqualTo(403);
        verify(jwtService, never()).createPassengerToken(
                any(Long.class), any(), any(Long.class), any(), any(Integer.class), any());
    }

    @Test
    void websocketTokenIsRejectedWhenRealtimeChannelDisabled() {
        wsProperties.setEnabled(false);

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> service.issueWsToken(new PassengerAuthContext(7L, "", 13L, NORMAL, 1, null)));

        assertThat(error.getErrorCode()).isEqualTo(503);
        verify(jwtService, never()).createPassengerToken(
                any(Long.class), any(), any(Long.class), any(), any(Integer.class), any());
    }

    @Test
    void commitsEpochThenClosesLocalWsThenProcessesOrders() {
        when(authStateClient.logout(new InternalLogoutRequest(7L, 9L)))
                .thenReturn(ResponseVo.success(new InternalLogoutResponse(7L, 10L)));
        when(passengerOrderService.cancelInFlightOrdersOnPassengerLogout(7L))
                .thenReturn(new PassengerLogoutResult());

        PassengerLogoutResult result = service.logout(7L, 9L);

        assertThat(result.isLoggedOut()).isTrue();
        assertThat(result.isOrderCleanupPending()).isFalse();
        InOrder order = inOrder(authStateClient, sessions, passengerOrderService);
        order.verify(authStateClient).logout(new InternalLogoutRequest(7L, 9L));
        order.verify(sessions).closeCustomerSessions(7L, "logout");
        order.verify(passengerOrderService).cancelInFlightOrdersOnPassengerLogout(7L);
    }

    @Test
    void staleLogoutDoesNotCloseTheNewSessionOrTouchOrders() {
        when(authStateClient.logout(new InternalLogoutRequest(7L, 9L)))
                .thenReturn(new ResponseVo<>(409, "认证状态已变化，请刷新后重试"));

        BizErrorException error = assertThrows(BizErrorException.class, () -> service.logout(7L, 9L));

        assertThat(error.getErrorCode()).isEqualTo(409);
        verify(sessions, never()).closeCustomerSessions(any(Long.class), any());
        verify(passengerOrderService, never()).cancelInFlightOrdersOnPassengerLogout(any(Long.class));
    }

    @Test
    void orderFailureDoesNotAttemptToRestoreEpoch() {
        when(authStateClient.logout(new InternalLogoutRequest(7L, 9L)))
                .thenReturn(ResponseVo.success(new InternalLogoutResponse(7L, 10L)));
        when(passengerOrderService.cancelInFlightOrdersOnPassengerLogout(7L))
                .thenThrow(new BizErrorException(502, "order unavailable"));

        PassengerLogoutResult result = service.logout(7L, 9L);

        assertThat(result.isLoggedOut()).isTrue();
        assertThat(result.isOrderCleanupPending()).isTrue();
        verify(authStateClient, times(1)).logout(any());
        verifyNoMoreInteractions(authStateClient);
    }

    private static AppAuthCustomerBrief brief(long id,
                                               String phone,
                                               long authEpoch,
                                               String scope,
                                               String operationNo) {
        AppAuthCustomerBrief customer = new AppAuthCustomerBrief();
        customer.setId(id);
        customer.setPhone(phone);
        customer.setNickname("乘客");
        customer.setAuthEpoch(authEpoch);
        customer.setScope(scope);
        customer.setOperationNo(operationNo);
        return customer;
    }
}
